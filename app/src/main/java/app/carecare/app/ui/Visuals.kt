package app.carecare.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.carecare.domain.AgentState
import app.carecare.domain.Playbooks
import app.carecare.domain.Powertrain
import app.carecare.domain.ReminderStatus
import app.carecare.domain.SituationVisual
import app.carecare.domain.TireUnits
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * 상황별 화면. 값은 도메인이 [SituationVisual]로 넘기고 여기서는 그리기만 한다.
 */

private val hm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private fun kmText(v: Double) = "%,.0f km".format(v)

/** 대화 중인 상황의 화면 */
@Composable
fun VisualPanel(v: SituationVisual, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(C.surface, RoundedCornerShape(18.dp)).padding(20.dp)) {
        when (v) {
            is SituationVisual.Tires -> { Title("타이어 공기압"); TireDiagram(v) }
            is SituationVisual.Energy -> EnergyGauge(v)
            is SituationVisual.MobileCharge -> MobileChargeCard(v)
            is SituationVisual.Visit -> VisitCard(v)
            is SituationVisual.Due -> DueBar(v)
            is SituationVisual.Booking -> BookingCard(v)
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, fontSize = 20.sp, color = C.text, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Note(text: String, color: Color = C.dim) = Text(text, fontSize = 14.sp, color = color)

@Composable
private fun Badge(text: String, color: Color) {
    Box(Modifier.border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(text, fontSize = 15.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------- 타이어

/** 차를 위에서 본 그림과 바퀴별 공기압. 낮은 바퀴는 빨간색 */
@Composable
fun TireDiagram(v: SituationVisual.Tires, compact: Boolean = false) {
    if (v.kpa.isEmpty()) { Note("바퀴별 공기압 정보가 아직 없어요"); return }
    val carW = if (compact) 70.dp else 110.dp
    val carH = if (compact) 130.dp else 200.dp
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(carH * 0.45f), horizontalAlignment = Alignment.End) {
            WheelLabel("LEFT_FRONT", v, compact); WheelLabel("LEFT_REAR", v, compact)
        }
        Canvas(Modifier.width(carW).height(carH)) {
            val body = Size(size.width * 0.62f, size.height)
            val left = (size.width - body.width) / 2
            drawRoundRect(C.surface2, Offset(left, 0f), body, CornerRadius(body.width * 0.35f))
            drawRoundRect(C.dim, Offset(left, 0f), body, CornerRadius(body.width * 0.35f), style = Stroke(3f))
            // 앞 유리
            drawRoundRect(C.dim.copy(alpha = 0.5f), Offset(left + body.width * 0.15f, body.height * 0.2f), Size(body.width * 0.7f, body.height * 0.14f), CornerRadius(8f))
            val ww = size.width * 0.16f
            val wh = size.height * 0.2f
            fun wheel(x: Float, y: Float, id: String) {
                val color = when (id) { in v.low -> C.danger; in v.advise -> C.warn; else -> C.ok }
                drawRoundRect(color, Offset(x, y), Size(ww, wh), CornerRadius(6f))
            }
            wheel(left - ww * 0.6f, size.height * 0.12f, "LEFT_FRONT")
            wheel(left + body.width - ww * 0.4f, size.height * 0.12f, "RIGHT_FRONT")
            wheel(left - ww * 0.6f, size.height * 0.68f, "LEFT_REAR")
            wheel(left + body.width - ww * 0.4f, size.height * 0.68f, "RIGHT_REAR")
        }
        Column(verticalArrangement = Arrangement.spacedBy(carH * 0.45f)) {
            WheelLabel("RIGHT_FRONT", v, compact); WheelLabel("RIGHT_REAR", v, compact)
        }
    }
    if (!compact) {
        Spacer(Modifier.height(10.dp))
        Note(v.referenceKpa?.let { "권장 ${TireUnits.psiText(it)} 기준 · 88% 이하면 관리 필요(경고등 전), 75% 이하면 점검 필요" } ?: "권장 공기압을 모르면 낮음을 판단하지 않아요")
    }
}

@Composable
private fun WheelLabel(id: String, v: SituationVisual.Tires, compact: Boolean) {
    val low = id in v.low
    val advise = id in v.advise
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(Playbooks.wheelName(id), fontSize = if (compact) 12.sp else 15.sp, color = C.dim)
        Text(v.kpa[id]?.let { TireUnits.psiText(it) } ?: "-", fontSize = if (compact) 16.sp else 22.sp,
            color = when { low -> C.danger; advise -> C.warn; else -> C.text }, fontWeight = if (low || advise) FontWeight.Bold else FontWeight.Normal)
    }
}

// ---------------------------------------------------------------- 잔량·주행가능거리

@Composable
fun EnergyGauge(v: SituationVisual.Energy) {
    Title(if (v.ev) "배터리와 주행가능거리" else "연료와 주행가능거리")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(v.percent?.let { "$it%" } ?: "-", fontSize = 40.sp, color = if ((v.percent ?: 100) <= 15) C.warn else C.text, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Bar((v.percent ?: 0) / 100f, if ((v.percent ?: 100) <= 15) C.warn else C.accent)
            Spacer(Modifier.height(4.dp))
            Note(if (v.ev) "배터리 잔량" else "연료 잔량")
        }
    }
    Spacer(Modifier.height(14.dp))
    val range = v.rangeKm
    val target = v.targetKm
    if (range != null && target != null) {
        val max = maxOf(range, target * 1.2)
        LabeledBar("주행가능거리", kmText(range), (range / max).toFloat(), C.accent)
        Spacer(Modifier.height(8.dp))
        LabeledBar("${v.targetName}까지", kmText(target), (target / max).toFloat(), C.dim)
        Spacer(Modifier.height(4.dp))
        Note("여유 기준: 거리의 1.2배 (${kmText(target * 1.2)})")
        Spacer(Modifier.height(10.dp))
        val (label, color) = when {
            v.enough == true -> "충분해요" to C.ok
            range >= target -> "빠듯해요" to C.warn
            else -> "부족해요" to C.danger
        }
        Badge(label, color)
    } else if (range != null) {
        Text("주행가능거리 ${kmText(range)}", fontSize = 22.sp, color = C.text)
    } else Note("주행가능거리 정보가 아직 없어요")
    v.basisLabel?.let { Spacer(Modifier.height(8.dp)); Note("근거: $it", C.warn) }
}

@Composable
private fun Bar(fraction: Float, color: Color) {
    Box(Modifier.fillMaxWidth().height(14.dp).background(C.surface2, RoundedCornerShape(7.dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(14.dp).background(color, RoundedCornerShape(7.dp)))
    }
}

@Composable
private fun LabeledBar(label: String, value: String, fraction: Float, color: Color) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 16.sp, color = C.text, modifier = Modifier.weight(1f))
        Text(value, fontSize = 16.sp, color = C.text, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(4.dp))
    Bar(fraction, color)
}

// ---------------------------------------------------------------- 찾아가는 충전

@Composable
fun MobileChargeCard(v: SituationVisual.MobileCharge) {
    Title("현대자동차 찾아가는 충전")
    Text(v.restAreaName + (v.distanceKm?.let { " · ${Playbooks.distanceText(it)}" } ?: ""), fontSize = 22.sp, color = C.text)
    Spacer(Modifier.height(10.dp))
    if (v.eta == null) Badge("요청 전 · 승인을 기다리는 중", C.warn)
    else Badge("출동 차량 도착 예정 ${hm.format(v.eta)}", C.ok)
    Spacer(Modifier.height(10.dp))
    Note("요청 내용: 차량 위치, 배터리 잔량, 만날 휴게소")
    Note("조건: ${v.terms}")
    Spacer(Modifier.height(6.dp))
    Note("체험용 모의 응답입니다. 실제로 접수하지 않아요(실제 신청: 고객센터 080-600-6000, myHyundai, 블루링크 SOS)", C.warn)
}

// ---------------------------------------------------------------- 정비소 방문

@Composable
fun VisitCard(v: SituationVisual.Visit) {
    Title("정비소 방문 감지")
    Text(v.placeName, fontSize = 24.sp, color = C.text)
    Spacer(Modifier.height(6.dp))
    Text("머문 시간 ${v.dwellMinutes}분", fontSize = 20.sp, color = C.accent)
    Spacer(Modifier.height(10.dp))
    Note("위치 사용에 동의한 경우만 감지해요. 이동 경로는 저장하지 않아요")
    Note("받은 정비는 운전자가 확인한 뒤에만 기록해요")
}

// ---------------------------------------------------------------- 교환 시기

@Composable
fun DueBar(v: SituationVisual.Due) {
    Title("소모품 교환 시기")
    Text(Playbooks.joinAnd(v.items.map { Playbooks.itemName(it) }), fontSize = 22.sp, color = C.text)
    Spacer(Modifier.height(12.dp))
    val last = v.lastServiceKm
    val due = v.dueKm
    val cur = v.currentKm
    if (last != null && due != null && cur != null && due > last) {
        val end = maxOf(due, cur)
        val frac = ((cur - last) / (end - last)).toFloat()
        Bar(frac, if (cur >= due) C.warn else C.accent)
        if (cur < due) { Spacer(Modifier.height(4.dp)); Text("교환까지 ${kmText(due - cur)} 남음", fontSize = 15.sp, color = C.accent) }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { Note("지난 교환"); Text(kmText(last), fontSize = 16.sp, color = C.text) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Note("교환 시점"); Text(kmText(due), fontSize = 16.sp, color = C.warn) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) { Note("지금"); Text(kmText(cur), fontSize = 16.sp, color = C.text) }
        }
    } else Note("주행거리 정보가 부족해 막대를 그리지 않아요")
    Spacer(Modifier.height(8.dp))
    Note(if (v.oem) "기준: 제조사 권장 주기(현대자동차 오너스 매뉴얼, 통상 조건)" else "기준: 내가 설정한 주기")
}

// ---------------------------------------------------------------- 정비소 예약

private val slotFmt = DateTimeFormatter.ofPattern("M월 d일 (E) HH:mm", java.util.Locale.KOREAN)

@Composable
fun BookingCard(v: SituationVisual.Booking) {
    Title(if (v.confirmed) "정비소 예약 요청됨" else "정비소 예약")
    Text(v.shopName, fontSize = 22.sp, color = C.text)
    v.distanceKm?.let { Note("여기서 ${Playbooks.distanceText(it)}") }
    Spacer(Modifier.height(8.dp))
    Text(slotFmt.format(v.slot), fontSize = 26.sp, color = if (v.confirmed) C.ok else C.accent, fontWeight = FontWeight.Bold)
    if (v.items.isNotEmpty()) Note("정비 품목: " + Playbooks.joinAnd(v.items.map { Playbooks.itemName(it) }))
    Spacer(Modifier.height(8.dp))
    Note(if (v.confirmed) "체험용 모의 응답이에요. 실제 정비소에 접수되지 않았어요" else "승인한 정비소·시간 그대로만 예약을 요청해요")
}

// ---------------------------------------------------------------- 대화가 없을 때: 차량 상태 요약

@Composable
fun VehicleSummary(s: AgentState, powertrain: Powertrain) {
    Column(Modifier.fillMaxWidth().background(C.surface, RoundedCornerShape(18.dp)).padding(20.dp)) {
        Title("차량 상태 요약")
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column {
                Note("타이어")
                Spacer(Modifier.height(6.dp))
                TireDiagram(s.tires, compact = true)
            }
            Column(Modifier.weight(1f)) {
                // 플러그인 하이브리드는 연료를 모르면 배터리를 보여 준다
                val ev = powertrain == Powertrain.EV || (powertrain == Powertrain.PHEV && s.fuelPercent == null && s.evSoc != null)
                Note(if (ev) "배터리" else "연료")
                val pct = if (ev) s.evSoc else s.fuelPercent
                Text(pct?.let { "$it%" } ?: "정보 없음", fontSize = 26.sp, color = C.text, fontWeight = FontWeight.SemiBold)
                if (pct != null) Bar(pct / 100f, if (pct <= 15) C.warn else C.accent)
                Spacer(Modifier.height(6.dp))
                Note("주행가능거리 ${s.rangeKm?.let { kmText(it) } ?: "정보 없음"}")
                Spacer(Modifier.height(14.dp))
                Note("다음 교환")
                val next = s.reminders.filter { it.dueOdometerKm != null && it.status != ReminderStatus.NO_INTERVAL }.minByOrNull { it.dueOdometerKm!! }
                Text(next?.let { "${Playbooks.itemName(it.itemCode)} · ${kmText(it.dueOdometerKm!!)}" } ?: "기록이 생기면 계산해요", fontSize = 17.sp, color = C.text)
            }
        }
        Spacer(Modifier.height(8.dp))
        // '정상'이라고 단언하지 않는다 (UX-02)
        Note("받은 신호만 보여 줘요. 차량 계기판 경고가 우선이에요")
    }
}
