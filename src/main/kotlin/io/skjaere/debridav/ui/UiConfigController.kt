package io.skjaere.debridav.ui

import kotlinx.coroutines.runBlocking
import org.springframework.boot.info.BuildProperties
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
class UiConfigController(
    private val uiConfig: UiConfigurationProperties,
    private val grafanaDashboardService: GrafanaDashboardService,
    private val buildProperties: BuildProperties? = null,
) {
    @GetMapping("/ui-config")
    fun getUiConfig(): ResponseEntity<UiConfigDto> {
        val grafana = uiConfig.grafana
            .takeIf { it.baseUrl.isNotBlank() }
            ?.let { cfg -> GrafanaDto(baseUrl = cfg.baseUrl.trimEnd('/')) }
        return ResponseEntity.ok(
            UiConfigDto(
                grafana = grafana,
                version = buildProperties?.version,
            )
        )
    }

    @GetMapping("/grafana/dashboards")
    fun getGrafanaDashboards(): ResponseEntity<List<DashboardDto>> =
        ResponseEntity.ok(runBlocking { grafanaDashboardService.listDashboards() })
}

data class UiConfigDto(val grafana: GrafanaDto?, val version: String?)
data class GrafanaDto(val baseUrl: String)
data class DashboardDto(val label: String, val path: String)
