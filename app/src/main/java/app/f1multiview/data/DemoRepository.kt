package app.f1multiview.data
import app.f1multiview.model.*
object DemoRepository {
    val sessions=listOf(
        Session("race","Race","🇺🇸 United States","Sun 5 Oct",true),
        Session("qualifying","Qualifying","🇺🇸 United States","Sat 4 Oct",false),
        Session("sprint","Sprint","🇺🇸 United States","Sat 4 Oct",false),
        Session("practice3","Practice 3","🇺🇸 United States","Sat 4 Oct",false)
    )
    val streams=listOf(
        StreamSource("world","WORLD FEED",StreamKind.WORLD),
        StreamSource("ver","VER • ONBOARD",StreamKind.ONBOARD,driver="VER"),
        StreamSource("lec","LEC • ONBOARD",StreamKind.ONBOARD,driver="LEC"),
        StreamSource("ham","HAM • ONBOARD",StreamKind.ONBOARD,driver="HAM"),
        StreamSource("nor","NOR • ONBOARD",StreamKind.ONBOARD,driver="NOR"),
        StreamSource("timing","LIVE TIMING",StreamKind.TIMING),
        StreamSource("track","TRACK MAP",StreamKind.TRACK)
    )
    fun telemetry()=listOf(
        DriverTelemetry("VER",311,11780,8,98,0,42,"1:21.441"),
        DriverTelemetry("LEC",307,11440,8,95,0,42,"1:21.883"),
        DriverTelemetry("HAM",304,11210,8,91,4,42,"1:22.104"),
        DriverTelemetry("NOR",309,11650,8,97,0,42,"1:21.700")
    )
    fun timing()=listOf(
        TimingRow(1,"VER","LEADER","1:21.441","MED",1),
        TimingRow(2,"LEC","+2.314","1:21.883","MED",1),
        TimingRow(3,"NOR","+5.202","1:21.700","HARD",1),
        TimingRow(4,"HAM","+8.811","1:22.104","MED",2),
        TimingRow(5,"PIA","+11.201","1:22.302","MED",1)
    )
    fun raceControl()=listOf(
        RaceControlEvent("42:18","OVERTAKE MODE ENABLED","INFO"),
        RaceControlEvent("41:52","Track limits under investigation: CAR 44","WARN"),
        RaceControlEvent("40:11","Yellow flag cleared","INFO"),
        RaceControlEvent("38:09","CAR 18 PIT LANE SPEEDING","WARN")
    )
}
