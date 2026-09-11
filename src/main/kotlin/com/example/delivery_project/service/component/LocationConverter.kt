package com.example.delivery_project.service.component

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

object LocationConverter {
    const val TO_GRID = 0
    const val TO_GPS = 1

    private const val RE = 6371.00877 // 지구 반경(km)
    private const val GRID = 5.0 // 격자 간격(km)
    private const val SLAT1 = 30.0 // 투영 위도1(degree)
    private const val SLAT2 = 60.0 // 투영 위도2(degree)
    private const val OLON = 126.0 // 기준점 경도(degree)
    private const val OLAT = 38.0 // 기준점 위도(degree)
    private const val XO = 43.0 // 기준점 X좌표(GRID)
    private const val YO = 136.0 // 기준점 Y좌표(GRID)

    private const val DEGRAD = PI / 180.0
    private const val RADDEG = 180.0 / PI

    // LCC DFS 좌표변환: TO_GRID(위경도 -> 격자), TO_GPS(격자 -> 위경도)
    fun convertGridGps(mode: Int, latX: Double, lngY: Double): LatXLngY {
        val re = RE / GRID
        val slat1 = SLAT1 * DEGRAD
        val slat2 = SLAT2 * DEGRAD
        val olon = OLON * DEGRAD
        val olat = OLAT * DEGRAD

        var sn = tan(PI * 0.25 + slat2 * 0.5) / tan(PI * 0.25 + slat1 * 0.5)
        sn = ln(cos(slat1) / cos(slat2)) / ln(sn)
        var sf = tan(PI * 0.25 + slat1 * 0.5)
        sf = sf.pow(sn) * cos(slat1) / sn
        var ro = tan(PI * 0.25 + olat * 0.5)
        ro = re * sf / ro.pow(sn)

        return if (mode == TO_GRID) {
            var ra = tan(PI * 0.25 + latX * DEGRAD * 0.5)
            ra = re * sf / ra.pow(sn)
            var theta = lngY * DEGRAD - olon
            if (theta > PI) {
                theta -= 2.0 * PI
            }
            if (theta < -PI) {
                theta += 2.0 * PI
            }
            theta *= sn

            LatXLngY(
                lat = latX,
                lng = lngY,
                x = floor(ra * sin(theta) + XO + 0.5),
                y = floor(ro - ra * cos(theta) + YO + 0.5),
            )
        } else {
            val xn = latX - XO
            val yn = ro - lngY + YO
            var ra = sqrt(xn * xn + yn * yn)
            if (sn < 0.0) {
                ra = -ra
            }
            var alat = (re * sf / ra).pow(1.0 / sn)
            alat = 2.0 * atan(alat) - PI * 0.5

            val theta = when {
                abs(xn) <= 0.0 -> 0.0
                abs(yn) <= 0.0 -> if (xn < 0.0) -PI * 0.5 else PI * 0.5
                else -> atan2(xn, yn)
            }

            val alon = theta / sn + olon
            LatXLngY(
                lat = alat * RADDEG,
                lng = alon * RADDEG,
                x = latX,
                y = lngY,
            )
        }
    }

    data class LatXLngY(
        val lat: Double,
        val lng: Double,
        val x: Double,
        val y: Double,
    )
}
