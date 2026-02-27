package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class MusicResponsiveHeaderRenderer(
    val thumbnail: ThumbnailRenderer?,
    val buttons: List<Button>,
    val title: Runs,
    val subtitle: Runs,
    val secondSubtitle: Runs?,
    val straplineTextOne: Runs?
) {
    @Serializable
    data class Button(
        val musicPlayButtonRenderer: MusicPlayButtonRenderer?,
        val menuRenderer: Menu.MenuRenderer?,
        val toggleButtonRenderer: ToggleButtonRenderer?,
    ) {
        @Serializable
        data class MusicPlayButtonRenderer(
            val playNavigationEndpoint: NavigationEndpoint?,
        )

        @Serializable
        data class ToggleButtonRenderer(
            val defaultIcon: Icon?,
            val defaultServiceEndpoint: DefaultServiceEndpoint?,
            val toggledServiceEndpoint: ToggledServiceEndpoint?,
        )
    }
}
