package dev.local.clauderelay

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

class OutboxJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Thread {
            val ok = OutboxLogic.drainOutbox(applicationContext)
            jobFinished(params, !ok) // reschedule (with backoff) if anything failed
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val PERIODIC_JOB_ID = 2001
        private const val IMMEDIATE_JOB_ID = 2002

        fun schedulePeriodic(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val job = JobInfo.Builder(PERIODIC_JOB_ID, ComponentName(context, OutboxJobService::class.java))
                .setPeriodic(15 * 60 * 1000L) // system-enforced minimum for periodic jobs
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
        }

        fun scheduleImmediate(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val job = JobInfo.Builder(IMMEDIATE_JOB_ID, ComponentName(context, OutboxJobService::class.java))
                .setMinimumLatency(0)
                .setOverrideDeadline(0)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(15_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            scheduler.schedule(job)
        }
    }
}
