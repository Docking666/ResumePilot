package com.resumepilot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resumepilot.app.ResumePilotApp
import com.resumepilot.app.data.DashboardData
import com.resumepilot.app.data.PlatformStat
import com.resumepilot.app.data.StatsService
import com.resumepilot.app.data.TodayStats
import kotlinx.coroutines.launch

/**
 * 数据看板界面——统计投递数据
 *
 * 展示内容：
 * 1. 总览卡片（总投递、成功率、今日统计）
 * 2. 平台分布（各平台投递情况）
 * 3. 近7日趋势
 * 4. 最近执行日志
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen() {
    val app = ResumePilotApp.instance
    val scope = rememberCoroutineScope()
    val statsService = remember { StatsService(app) }

    var dashboardData by remember { mutableStateOf<DashboardData?>(null) }
    var todayStats by remember { mutableStateOf<TodayStats?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // 加载数据
    LaunchedEffect(Unit) {
        isLoading = true
        dashboardData = statsService.getDashboardData()
        todayStats = statsService.getTodayStats()
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("数据看板", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            isLoading = true
                            dashboardData = statsService.getDashboardData()
                            todayStats = statsService.getTodayStats()
                            isLoading = false
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 总览卡片
                item {
                    OverviewCards(dashboardData, todayStats)
                }

                // 平台分布
                if (dashboardData?.platformSummary?.isNotEmpty() == true) {
                    item {
                        Text("平台分布", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        PlatformDistribution(dashboardData!!.platformSummary)
                    }
                }

                // 近7日趋势
                if (dashboardData?.dailyTrend?.isNotEmpty() == true) {
                    item {
                        Text("近7日趋势", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        DailyTrendChart(dashboardData!!.dailyTrend)
                    }
                }

                // 最近执行日志
                if (dashboardData?.recentLogs?.isNotEmpty() == true) {
                    item {
                        Text("最近执行", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    items(dashboardData!!.recentLogs.take(10)) { log ->
                        ExecutionLogItem(log)
                    }
                }

                // 空状态
                if (dashboardData?.totalAttempts == 0) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.BarChart,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("暂无数据", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                Text(
                                    "执行投递后统计数据将在此显示",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ====== 子组件 ======

@Composable
private fun OverviewCards(data: DashboardData?, today: TodayStats?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Send,
            label = "总投递",
            value = "${data?.totalAttempts ?: 0}",
            color = MaterialTheme.colorScheme.primary
        )
        StatCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.CheckCircle,
            label = "成功率",
            value = "${data?.successRate ?: 0}%",
            color = MaterialTheme.colorScheme.tertiary
        )
        StatCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Today,
            label = "今日",
            value = "${today?.total ?: 0}",
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun PlatformDistribution(platforms: List<PlatformStat>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            platforms.forEach { stat ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val total = stat.success + stat.fail
                    val rate = if (total > 0) stat.success * 100 / total else 0
                    val progress = if (total > 0) stat.success.toFloat() / total else 0f

                    Text(stat.platform, modifier = Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.width(8.dp))

                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "$rate%",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(36.dp),
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}

@Composable
private fun DailyTrendChart(trends: List<com.resumepilot.app.data.DailyTrend>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            trends.forEach { trend ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        trend.date.takeLast(5),
                        modifier = Modifier.width(52.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))

                    // 成功柱
                    if (trend.success > 0) {
                        Box(
                            modifier = Modifier
                                .width((trend.success * 8).dp.coerceAtLeast(4.dp))
                                .height(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    // 失败柱
                    if (trend.fail > 0) {
                        Spacer(modifier = Modifier.width(2.dp))
                        Box(
                            modifier = Modifier
                                .width((trend.fail * 8).dp.coerceAtLeast(4.dp))
                                .height(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.error)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "${trend.success}/${trend.fail}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ExecutionLogItem(log: com.resumepilot.app.data.StatsLog) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (log.success)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (log.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(log.date, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(72.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            Text(log.platform, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(
                if (log.success) "成功" else "失败",
                style = MaterialTheme.typography.labelSmall,
                color = if (log.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }
}