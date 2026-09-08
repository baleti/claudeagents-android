package dev.local.claudeagents

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * Periodic background sync via the platform JobScheduler (no WorkManager --
 * androidx isn't available in this gradle-less build, but JobScheduler is
 * plain android.app.job and does everything we need: periodic scheduling,
 * a network constraint, exponential backoff, and persistence across reboot).
 */
class SyncJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Thread {
            SyncLogic.performSync(applicationContext)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val PERIODIC_JOB_ID = 1001
        private const val IMMEDIATE_JOB_ID = 1002

        // 15 minutes is Android's own documented floor for a periodic
        // JobScheduler job (JobInfo.getMinPeriodMillis()) -- asking for
        // less just gets silently clamped to this anyway. Was 60 minutes
        // (an arbitrary "roughly hourly" choice, not a deliberate floor),
        // which meant a background stretch shorter than an hour could
        // genuinely see zero sync rounds at all -- confirmed live
        // 2026-09-07 as the actual cause of "conversations aren't synced
        // even though it had plenty of time in the background": 45 minutes
        // backgrounded, one 60-minute job, zero fires. Re-called from
        // MainActivity.onResume() too (not just first pairing) so an
        // already-paired install picks up the new interval on its next
        // launch -- scheduling the same job ID again just replaces the
        // pending definition, it doesn't stack a second job.
        fun schedulePeriodic(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val job = JobInfo.Builder(PERIODIC_JOB_ID, ComponentName(context, SyncJobService::class.java))
                .setPeriodic(15 * 60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
        }

        fun scheduleImmediate(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val job = JobInfo.Builder(IMMEDIATE_JOB_ID, ComponentName(context, SyncJobService::class.java))
                .setMinimumLatency(0)
                .setOverrideDeadline(0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()
            scheduler.schedule(job)
        }
    }
}
