package dev.local.clauderelay

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

        fun schedulePeriodic(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val job = JobInfo.Builder(PERIODIC_JOB_ID, ComponentName(context, SyncJobService::class.java))
                .setPeriodic(60 * 60 * 1000L) // ~hourly; system may enforce a higher minimum
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
