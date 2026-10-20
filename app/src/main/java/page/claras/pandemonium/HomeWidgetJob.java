package page.claras.pandemonium;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

public final class HomeWidgetJob extends JobService {
    private static final int PERIODIC = 42210, REFRESH = 42211;
    private final java.util.Set<JobParameters> active = new java.util.HashSet<>();

    static void schedule(Context context, boolean immediate) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        ComponentName service = new ComponentName(context, HomeWidgetJob.class);
        if (jobs.getPendingJob(PERIODIC) == null) jobs.schedule(new JobInfo.Builder(PERIODIC, service).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(30 * 60_000L).setPersisted(true).build());
        if (immediate) jobs.schedule(new JobInfo.Builder(REFRESH, service).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(0).build());
    }
    static void cancel(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        jobs.cancel(PERIODIC); jobs.cancel(REFRESH);
    }
    @Override public boolean onStartJob(JobParameters params) {
        active.add(params);
        HomeWidgetSync.refresh(this, true, () -> { if (active.remove(params)) jobFinished(params, false); });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { active.remove(params); return true; }
}
