package com.metrolist.music.utils.cipher

import android.net.Uri
import timber.log.Timber

/**
 * Pure Kotlin implementation of YouTube signature deobfuscation.
 * No WebView or external JavaScript libraries required.
 *
 * YouTube signature cipher works by applying a series of operations
 * (reverse, splice, swap) defined in the player JS to the obfuscated signature.
 * We parse those operations directly from player.js and execute them in Kotlin.
 */
object SignatureDeobfuscator {
    private const val TAG = "Metrolist_SigDeobfusc"

    private enum class OpType { REVERSE, SPLICE, SWAP }
    private data class Op(val type: OpType, val arg: Int)

    // Cache: playerHash -> (sigFuncName, ops)
    @Volatile private var cachedHash: String? = null
    @Volatile private var cachedFuncName: String? = null
    @Volatile private var cachedOps: List<Op>? = null

    /**
     * Parse the signatureCipher string and return the deobfuscated stream URL.
     *
     * @param signatureCipher  The "s=XXX&sp=sig&url=https://..." string from player response
     * @param playerJs         Raw player.js content
     * @param playerHash       Player hash used for caching (may be empty, cache skipped)
     * @param sigInfo          Signature function name + optional constant arg
     * @return Full stream URL with deobfuscated signature, or null on failure
     */
    fun deobfuscateUrl(
        signatureCipher: String,
        playerJs: String,
        playerHash: String,
        sigInfo: FunctionNameExtractor.SigFunctionInfo,
    ): String? {
        val params = parseQueryParams(signatureCipher)
        val obfuscatedSig = params["s"] ?: run {
            Timber.tag(TAG).e("No 's' param in signatureCipher")
            return null
        }
        val sigParam = params["sp"] ?: "signature"
        val baseUrl = params["url"] ?: run {
            Timber.tag(TAG).e("No 'url' param in signatureCipher")
            return null
        }

        val ops = resolveOps(playerJs, playerHash, sigInfo) ?: return null

        val chars = obfuscatedSig.toMutableList()
        for (op in ops) {
            when (op.type) {
                OpType.REVERSE -> chars.reverse()
                OpType.SPLICE -> {
                    val count = op.arg.coerceIn(0, chars.size)
                    repeat(count) { chars.removeAt(0) }
                }
                OpType.SWAP -> {
                    if (chars.isNotEmpty()) {
                        val idx = op.arg % chars.size
                        val tmp = chars[0]
                        chars[0] = chars[idx]
                        chars[idx] = tmp
                    }
                }
            }
        }

        val deobfuscatedSig = chars.joinToString("")
        Timber.tag(TAG).d(
            "Sig deobfuscated (${ops.size} ops): " +
                "${obfuscatedSig.take(20)}... -> ${deobfuscatedSig.take(20)}..."
        )

        val separator = if ("?" in baseUrl) "&" else "?"
        return "$baseUrl${separator}${sigParam}=${Uri.encode(deobfuscatedSig)}"
    }

    fun invalidateCache() {
        cachedHash = null
        cachedFuncName = null
        cachedOps = null
    }

    // ─── Private helpers ───────────────────────────────────────────────────────

    private fun resolveOps(
        playerJs: String,
        playerHash: String,
        sigInfo: FunctionNameExtractor.SigFunctionInfo,
    ): List<Op>? {
        // Cache hit: same player JS + same function
        if (playerHash.isNotEmpty() &&
            cachedHash == playerHash &&
            cachedFuncName == sigInfo.name &&
            cachedOps != null
        ) {
            return cachedOps
        }

        val ops = extractOps(playerJs, sigInfo) ?: return null
        cachedHash = playerHash
        cachedFuncName = sigInfo.name
        cachedOps = ops
        return ops
    }

    /**
     * Parse the three-step operation sequence from the player JS.
     *
     * Steps:
     *  1. Locate sig function body by brace-counting (handles minified code).
     *  2. Identify the helper operations object name from the first member call.
     *  3. Parse helper methods (reverse / splice / swap) by inspecting method bodies.
     *  4. Walk the function body line-by-line to build the ordered Op list.
     */
    private fun extractOps(
        playerJs: String,
        sigInfo: FunctionNameExtractor.SigFunctionInfo,
    ): List<Op>? {
        // 1. Get function body
        val funcBody = extractFunctionBody(playerJs, sigInfo.name) ?: run {
            Timber.tag(TAG).e("Cannot find function body for '${sigInfo.name}'")
            return null
        }
        Timber.tag(TAG).d("Function body for '${sigInfo.name}': ${funcBody.take(300)}")

        // 2. Find helper object name from the first `HELPER.METHOD(` call
        // Note: helper names can be 1 char in modern minified player JS, so use {1,}
        val helperCallRe = Regex("""([a-zA-Z0-9${'$'}]{1,})\.([a-zA-Z0-9${'$'}]{1,})\(""")
        val helperName = funcBody
            .splitToSequence(';', '\n')
            .firstOrNull { helperCallRe.containsMatchIn(it) }
            ?.let { helperCallRe.find(it)?.groupValues?.get(1) }
            ?: run {
                Timber.tag(TAG).e("Cannot find helper object name in function body")
                return null
            }
        Timber.tag(TAG).d("Helper object: '$helperName'")

        // 3. Extract helper object body
        val helperBody = extractHelperBody(playerJs, helperName) ?: run {
            Timber.tag(TAG).e("Cannot find helper object '$helperName'")
            return null
        }
        Timber.tag(TAG).d("Helper body: ${helperBody.take(400)}")

        // 4. Map method names → OpType
        val opMap = parseHelperMethods(helperBody)
        if (opMap.isEmpty()) {
            Timber.tag(TAG).e("Could not parse helper methods from: ${helperBody.take(200)}")
            return null
        }
        Timber.tag(TAG).d("Op map: $opMap")

        // 5. Walk function body and extract ordered operations
        // Call form 1 (with numeric arg): HELPER.METHOD(a, 5)
        val callWithArgRe = Regex(
            """${Regex.escape(helperName)}\.([a-zA-Z0-9${'$'}]+)\(\s*[a-zA-Z0-9${'$'}]+\s*,\s*(\d+)\s*\)"""
        )
        // Call form 2 (no extra arg, e.g. reverse): HELPER.METHOD(a)
        val callNoArgRe = Regex(
            """${Regex.escape(helperName)}\.([a-zA-Z0-9${'$'}]+)\(\s*[a-zA-Z0-9${'$'}]+\s*\)"""
        )

        val ops = mutableListOf<Op>()
        for (stmt in funcBody.splitToSequence(';')) {
            val mWith = callWithArgRe.find(stmt)
            if (mWith != null) {
                val method = mWith.groupValues[1]
                val arg = mWith.groupValues[2].toIntOrNull() ?: 0
                val type = opMap[method] ?: continue
                ops += Op(type, arg)
                continue
            }
            val mNo = callNoArgRe.find(stmt)
            if (mNo != null) {
                val method = mNo.groupValues[1]
                val type = opMap[method] ?: continue
                ops += Op(type, 0)
            }
        }

        if (ops.isEmpty()) {
            Timber.tag(TAG).e("No operations extracted from function body")
            return null
        }
        Timber.tag(TAG).d("Extracted ${ops.size} operations: $ops")
        return ops
    }

    /**
     * Brace-counting extraction of a named function body.
     * Works regardless of minification.
     */
    private fun extractFunctionBody(playerJs: String, funcName: String): String? {
        val startRe = Regex(
            """${Regex.escape(funcName)}\s*=\s*function\s*\([^)]*\)\s*\{"""
        )
        val startMatch = startRe.find(playerJs) ?: return null
        val bodyStart = startMatch.range.last + 1  // index right after opening '{'

        var depth = 1
        var i = bodyStart
        while (i < playerJs.length && depth > 0) {
            when (playerJs[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        if (depth != 0) return null
        return playerJs.substring(bodyStart, i - 1) // content between { and }
    }

    /**
     * Brace-counting extraction of a plain object literal: `var NAME = {...}`.
     */
    private fun extractHelperBody(playerJs: String, helperName: String): String? {
        val startRe = Regex(
            """(?:var\s+)?${Regex.escape(helperName)}\s*=\s*\{"""
        )
        val startMatch = startRe.find(playerJs) ?: return null
        val bodyStart = startMatch.range.last + 1

        var depth = 1
        var i = bodyStart
        while (i < playerJs.length && depth > 0) {
            when (playerJs[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        if (depth != 0) return null
        return playerJs.substring(bodyStart, i - 1)
    }

    /**
     * Determine the operation type of each helper method by inspecting its body.
     *
     * YouTube always uses exactly three operation types:
     *  - REVERSE: `a.reverse()`
     *  - SPLICE:  `a.splice(0, b)` (removes first b characters)
     *  - SWAP:    swaps a[0] with a[b % a.length]
     */
    private fun parseHelperMethods(helperBody: String): Map<String, OpType> {
        val opMap = mutableMapOf<String, OpType>()

        // Each method: NAME:function(a,b){BODY}  (single-depth body assumed for helpers)
        val methodRe = Regex(
            """([a-zA-Z0-9${'$'}]+)\s*:\s*function\s*\([^)]*\)\s*\{([^}]+)\}"""
        )
        for (m in methodRe.findAll(helperBody)) {
            val name = m.groupValues[1]
            val body = m.groupValues[2]
            val type = when {
                ".reverse(" in body                                    -> OpType.REVERSE
                ".splice(" in body                                     -> OpType.SPLICE
                // Swap: assigns a[0] and a[b%a.length]
                Regex("""[a-zA-Z]\[0\]""").containsMatchIn(body)      -> OpType.SWAP
                else                                                   -> continue
            }
            opMap[name] = type
            Timber.tag(TAG).d("  $name -> $type")
        }
        return opMap
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = Uri.decode(pair.substring(0, idx))
                val value = Uri.decode(pair.substring(idx + 1))
                result[key] = value
            }
        }
        return result
    }
}
