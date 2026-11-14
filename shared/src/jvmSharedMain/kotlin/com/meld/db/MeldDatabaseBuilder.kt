package com.meld.db

import androidx.room3.RoomDatabase

// Here rather than next to MeldDatabase in commonMain while iOS has no actual: it moves there with the module.
expect fun getMeldDatabaseBuilder(context: Any? = null): RoomDatabase.Builder<MeldDatabase>
