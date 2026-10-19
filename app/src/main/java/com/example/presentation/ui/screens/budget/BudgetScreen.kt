package com.example.presentation.ui.screens.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.infrastructure.persistence.dao.PeriodBalanceRow
import com.example.presentation.viewmodel.BudgetViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ============================================================================
 * BudgetScreen — STAGE 7 (the accounting budget core's FIRST user surface)
 * ============================================================================
 *
 * Six verified stages built the budget intelligence system (allocation
 * periods, holds, the append-only ledger, per-provider cost, reconciliation,
 * derived-state projection — schema v23..v28) with no UI consumer. This
 * screen is the READ-ONLY observatory the [BudgetViewModel] projects:
 *
 *  - PERIOD BALANCES: every allocation period with the live equation
 *    (limit / posted / holds / available) and the service's own utilization
 *    derivation — selecting a period drives the feeds below;
 *  - THE LEDGER: the selected period's append-only entry feed (type, effect,
 *    basis, currency, timestamp) — the actual financial records, never a
 *    fabricated total;
 *  - HOLDS: the selected period's reservations with their real statuses;
 *  - COST BY PROVIDER: the calculated cost over the pricing chain (I-37).
 *
 * Honesty contract: an empty database renders honest empty states ("لا توجد
 * فترات ميزانية بعد…") — never zeros-that-mean-nothing; NaN utilization
 * (a zero-limit monitoring period) renders as «مراقبة بلا سقف», the same
 * semantics the domain fixed.
 */
@Composable
fun BudgetScreen(
    viewModel: BudgetViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val periodBalances by viewModel.periodBalances.collectAsState()
    val costByProvider by viewModel.costByProvider.collectAsState()
    val ledgerEntries by viewModel.ledgerEntries.collectAsState()
    val holds by viewModel.holds.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("budget_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------- Header ----------
        item {
            Column(modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp)) {
                Text(
                    text = "الميزانية المحاسبية",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "أرصدة فترات التخصيص ودفتر القيود والحجوزات وتكلفة المزوّدين — من النظام المحاسبي مباشرة (v23..v28)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---------- Period balances ----------
        item {
            SectionTitle(text = "أرصدة فترات التخصيص (${periodBalances.size})")
        }
        if (periodBalances.isEmpty()) {
            item { HonestEmptyCard(text = "لا توجد فترات ميزانية بعد — تُنشأ الفترة عند تخصيص ميزانية لمساحة العمل من شاشة الحوكمة.") }
        }
        items(periodBalances, key = { it.periodId }) { row ->
            PeriodBalanceCard(
                row = row,
                selected = row.periodId == state.selectedPeriodId,
                utilization = viewModel.utilization(row),
                onClick = { viewModel.selectPeriod(row.periodId) }
            )
        }

        // ---------- Ledger (selected period) ----------
        if (state.selectedPeriodId != null) {
            item {
                SectionTitle(text = "دفتر القيود — الفترة ${state.selectedPeriodId}")
            }
            if (ledgerEntries.isEmpty()) {
                item { HonestEmptyCard(text = "لا قيود في هذه الفترة بعد — يُقيَّد الاستهلاك فعلياً عند تنفيذ الطلبات عبر بوابات الميزانية.") }
            } else {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .testTag("budget_ledger_card"),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            ledgerEntries.take(50).forEachIndexed { index, entry ->
                                LedgerEntryRow(entry = entry)
                                if (index < minOf(ledgerEntries.size, 50) - 1) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                }
                            }
                            if (ledgerEntries.size > 50) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "…و${ledgerEntries.size - 50} قيداً أقدم (سجل ملاحقة فقط)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ---------- Holds (selected period) ----------
            item {
                SectionTitle(text = "الحجوزات (${holds.size})")
            }
            if (holds.isEmpty()) {
                item { HonestEmptyCard(text = "لا حجوزات نشطة في هذه الفترة — تُنشأ الحجوزات عند بدء تنفيذ يستهلك من الميزانية.") }
            } else {
                items(holds, key = { it.holdId }) { hold ->
                    HoldRow(hold = hold)
                }
            }
        }

        // ---------- Cost by provider ----------
        item {
            SectionTitle(text = "التكلفة المحسوبة لكل مزوّد (${costByProvider.size})")
        }
        if (costByProvider.isEmpty()) {
            item { HonestEmptyCard(text = "لا حسابات تكلفة بعد — تُحتسب التكلفة عبر سلسلة التسعير (المرحلة الثانية) عند تنفيذ طلبات فعلية.") }
        } else {
            items(costByProvider, key = { it.providerId }) { row ->
                ProviderCostCard(row = row)
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

// ---------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
private fun HonestEmptyCard(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp)
        )
    }
}

/** Micro units (the ledger's fixed-point domain) → a readable money string. */
private fun microToText(micro: Long, currency: String): String {
    val whole = micro / 1_000_000
    val fraction = ((micro % 1_000_000).toDouble().let { if (it < 0) -it else it } / 10_000).toInt()
    return "%s %d.%02d".format(currency, whole, fraction)
}

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(epochMs))

@Composable
private fun PeriodBalanceCard(
    row: PeriodBalanceRow,
    selected: Boolean,
    utilization: com.example.application.budget.BudgetDashboardService.Utilization,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick)
            .testTag("budget_period_${row.periodId}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (selected) Icons.Default.Savings else Icons.Default.AttachMoney,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = row.periodId,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (selected) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "المعروضة",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "الفترة: ${formatDate(row.periodStartEpochMs)} ← ${formatDate(row.periodEndEpochMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BalanceStat(
                    icon = Icons.Default.Savings,
                    label = "السقف",
                    value = microToText(row.limitMicroUnits, "و.ن"),
                    modifier = Modifier.weight(1f)
                )
                BalanceStat(
                    icon = Icons.Default.Payments,
                    label = "المرحّل",
                    value = microToText(row.postedEffectsMicroUnits, "و.ن"),
                    modifier = Modifier.weight(1f)
                )
                BalanceStat(
                    icon = Icons.Default.LockClock,
                    label = "المحجوز",
                    value = microToText(row.remainingHoldMicroUnits, "و.ن"),
                    modifier = Modifier.weight(1f)
                )
                BalanceStat(
                    icon = Icons.Default.CheckCircle,
                    label = "المتاح",
                    value = microToText(row.availableMicroUnits(), "و.ن"),
                    modifier = Modifier.weight(1f),
                    highlight = true
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            UtilizationLine(
                postedToLimit = utilization.postedToLimit,
                holdsToLimit = utilization.holdsToLimit
            )
        }
    }
}

/** The utilization derivation type alias keeps the card decoupled from VM internals. */

@Composable
private fun UtilizationLine(postedToLimit: Float, holdsToLimit: Float) {
    val postedPct = if (postedToLimit.isNaN()) null else (postedToLimit * 100).toInt()
    val holdsPct = if (holdsToLimit.isNaN()) null else (holdsToLimit * 100).toInt()
    val text = when {
        postedPct == null && holdsPct == null -> "استخدام: مراقبة بلا سقف"
        else -> buildString {
            append("استخدام: ")
            append(postedPct?.let { "$it%" } ?: "—")
            append(" مرحّل • ")
            append(holdsPct?.let { "$it%" } ?: "—")
            append(" محجوز")
        }
    }
    val warning = (postedPct ?: 0) >= 80 || (holdsPct ?: 0) >= 80
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = when {
            warning -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}

@Composable
private fun BalanceStat(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (highlight) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LedgerEntryRow(entry: com.example.infrastructure.persistence.entities.BudgetLedgerEntryEntity) {
    val (typeLabel, typeColor) = when (entry.entryType) {
        "SETTLE" -> "تسوية" to MaterialTheme.colorScheme.primary
        "RELEASE" -> "تحرير" to MaterialTheme.colorScheme.tertiary
        "CONSUMPTION" -> "استهلاك" to MaterialTheme.colorScheme.secondary
        "REVERSAL" -> "قيد عكسي" to MaterialTheme.colorScheme.error
        "ADJUSTMENT" -> "تسوية يدوية" to Color(0xFFF59E0B)
        else -> entry.entryType to MaterialTheme.colorScheme.outline
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .testTag("budget_entry_${entry.entryId}")
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = typeLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = typeColor
            )
            Text(
                text = "${formatDate(entry.entryTimestampEpochMs)} • الأساس: ${entry.entryBasis} • ${entry.currency}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = microToText(entry.availableEffectMicroUnits, entry.currency),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (entry.availableEffectMicroUnits >= 0) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun HoldRow(hold: com.example.infrastructure.persistence.entities.BudgetHoldEntity) {
    val (statusLabel, statusColor) = when (hold.status) {
        "ACTIVE" -> "نشط" to MaterialTheme.colorScheme.primary
        "SETTLED" -> "مُسوّى" to MaterialTheme.colorScheme.tertiary
        "PARTIALLY_SETTLED" -> "مُسوّى جزئياً" to Color(0xFFF59E0B)
        "RELEASED" -> "محرَّر" to MaterialTheme.colorScheme.outline
        "EXPIRED" -> "منتهٍ" to MaterialTheme.colorScheme.outline
        else -> hold.status to MaterialTheme.colorScheme.outline
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("budget_hold_${hold.holdId}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                if (hold.status == "ACTIVE") Icons.Default.Lock else Icons.Default.LockClock,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hold.reason ?: "حجز ${hold.holdId.takeLast(8)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = buildString {
                        append(formatDate(hold.createdAtEpochMs))
                        hold.expiresAtEpochMs?.let { append(" • ينتهي: ${formatDate(it)}") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = microToText(hold.holdAmountMicroUnits, hold.currency),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = statusColor.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderCostCard(row: com.example.infrastructure.persistence.dao.ProviderCostRow) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag("budget_provider_cost_${row.providerId}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp)
        ) {
            Icon(
                Icons.Default.AttachMoney,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.providerId,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${row.calculationCount} عملية احتساب عبر سلسلة التسعير",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = microToText(row.calculatedCostMicroUnits, "و.ن"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
