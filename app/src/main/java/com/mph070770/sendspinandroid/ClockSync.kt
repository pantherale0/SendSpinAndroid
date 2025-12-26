package com.mph070770.sendspinandroid

import kotlin.math.abs

/**
 * MVP clock sync:
 * server/time provides: client_transmitted, server_received, server_transmitted (all in µs; client timestamps are client clock).
 * We also know client_received (t3) when message arrives.
 *
 * We estimate one-way delay as (RTT - server_processing)/2 and align midpoints:
 *  - client_mid = t0 + RTT/2
 *  - server_mid = s1 + server_processing/2
 *
 * offset = server_mid - client_mid   (so: server_time ≈ client_time + offset)
 *
 * This matches the protocol intent: binary audio timestamps are server time and must be mapped to local time.
 */
class ClockSync {
    private data class Sample(
        val clientMidUs: Long,
        val offsetUs: Long
    )

    private val samples = ArrayDeque<Sample>(64)

    private var offsetUs: Long = 0
    private var driftPpm: Double = 0.0
    private var rttUs: Long = 0

    fun onServerTime(
        clientTransmittedUs: Long,
        clientReceivedUs: Long,
        serverReceivedUs: Long,
        serverTransmittedUs: Long
    ) {
        val t0 = clientTransmittedUs
        val t3 = clientReceivedUs
        val s1 = serverReceivedUs
        val s2 = serverTransmittedUs

        val rtt = (t3 - t0).coerceAtLeast(0)
        val serverProc = (s2 - s1).coerceAtLeast(0)
        val oneWay = ((rtt - serverProc) / 2).coerceAtLeast(0)

        val clientMid = t0 + rtt / 2
        val serverMid = s1 + serverProc / 2

        val off = serverMid - clientMid

        rttUs = rtt

        // Filter: clamp insane jumps, then keep a short history to estimate drift.
        if (samples.isNotEmpty()) {
            val last = samples.last().offsetUs
            val jump = abs(off - last)
            if (jump > 250_000) {
                // 250ms jump -> ignore (MVP protection)
                return
            }
        }

        samples.addLast(Sample(clientMidUs = clientMid, offsetUs = off))
        while (samples.size > 40) samples.removeFirst()

        // Smoothed offset: median-ish via average of last N (MVP)
        offsetUs = samples.map { it.offsetUs }.average().toLong()

        // Drift estimate via simple linear regression of offset vs time
        if (samples.size >= 10) {
            val xs = samples.map { it.clientMidUs.toDouble() }
            val ys = samples.map { it.offsetUs.toDouble() }

            val xMean = xs.average()
            val yMean = ys.average()

            var num = 0.0
            var den = 0.0
            for (i in xs.indices) {
                val dx = xs[i] - xMean
                num += dx * (ys[i] - yMean)
                den += dx * dx
            }

            val slope = if (den > 0) num / den else 0.0 // offset change per µs
            // slope is (µs offset)/(µs time) -> unitless; convert to ppm (parts per million)
            driftPpm = slope * 1_000_000.0
        }
    }

    fun estimatedOffsetUs(): Long = offsetUs
    fun estimatedDriftPpm(): Double = driftPpm
    fun estimatedRttUs(): Long = rttUs
}
