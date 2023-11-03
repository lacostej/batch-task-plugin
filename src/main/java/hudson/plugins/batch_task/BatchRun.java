package hudson.plugins.batch_task;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.*;
import hudson.model.*;
import hudson.model.Queue.Executable;
import hudson.slaves.WorkspaceList.Lease;
import hudson.tasks.BatchFile;
import hudson.tasks.CommandInterpreter;
import hudson.tasks.Shell;
import hudson.util.Iterators;

import org.jenkinsci.lib.envinject.EnvInjectException;
import org.jenkinsci.lib.envinject.service.EnvVarsResolver;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.framework.io.LargeText;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Record of {@link BatchTask} execution.
 *
 * @author Kohsuke Kawaguchi
 */
public final class BatchRun extends Actionable implements Executable, Comparable<BatchRun> {
    /**
     * Build result.
     * If null, we are still building.
     */
    protected Result result;

    public final Calendar timestamp;

    protected transient BatchRunAction parent;

    /**
     * Unique number that identifies this record among {@link BatchRunAction}.
     */
    public final int id;

    /**
     * Pointer that connects us back to {@link BatchTask}
     *
     * @see #getParent()
     */
    public final String taskName;

    /**
     * Number of milli-seconds it took to run this build.
     */
    protected long duration;

    transient BatchTask task;

    transient private RunAdapter run;

    protected BatchRun(Calendar timestamp, BatchRunAction parent, int id, BatchTask task) throws IOException {
        this.task = task;
        this.timestamp = timestamp;
        this.parent = parent;
        this.id = id;
        this.taskName = task.name;
        this.run = new RunAdapter(this);
    }

    public Result getResult() {
        return result;
    }

    public RunAdapter getUpdatedRun(BatchTask task) {
        this.task = task;
        this.run = RunAdapter.from(this);
        // run.setResult(result); // this fails as Run protects modifying the result
/*
	WARNING	h.ExpressionFactory2$JexlExpression#evaluate: Caught exception evaluating: it.historyPageFilter in /jenkins/job/test_batch_task/batchTasks/task/task%201/. Reason: java.lang.reflect.InvocationTargetException
java.lang.IllegalStateException: cannot change build result while in NOT_STARTED
	at hudson.model.Run.setResult(Run.java:505)
	at hudson.plugins.batch_task.BatchRun.getUpdatedRun(BatchRun.java:83)
	at hudson.plugins.batch_task.BatchRunAction.getRecords(BatchRunAction.java:65)
	*/
        return run;
    }

    public static class RunAdapter extends Run<BatchTask.JobAdapter, RunAdapter> {
        transient BatchRun run;

        // FIXME clean up API
        private RunAdapter(BatchRun batchRun) throws IOException {
            super(batchRun.task.job);
            run = batchRun;
            this.timestamp = batchRun.timestamp.getTimeInMillis();
        }
        private RunAdapter(BatchRun batchRun, Calendar calendar) {
            super(batchRun.task.job, calendar);
            super.number = batchRun.id;

            run = batchRun;
            this.timestamp = batchRun.timestamp.getTimeInMillis();
        }

        static RunAdapter from(BatchRun batchRun) {
            return new RunAdapter(batchRun, batchRun.timestamp);
        }

        public void setDuration(long duration) {
            this.duration = duration;
        }
    }
    /**
     * Is this task still running?
     */
    public boolean isRunning() {
        return result == null;
    }

    /**
     * Gets the string that says how long since this run has started.
     *
     * @return string like "3 minutes" "1 day" etc.
     */
    public String getTimestampString() {
        // FIXME run.getTimestampString();
        long time = new GregorianCalendar().getTimeInMillis() - timestamp.getTimeInMillis();
        return Util.getTimeSpanString(time);
    }

    /**
     * Gets the log file that stores the execution result.
     */
    public File getLogFile() {
        return new File(parent.owner.getRootDir(), "task-" + id + ".log");
    }

    @NonNull
    public BatchTask getParent() {
        BatchTaskAction jta = parent.owner.getProject().getAction(BatchTaskAction.class);
        return jta.getTask(taskName);
    }

    public BallColor getIconColor() {
        return run.getIconColor();
    }

    public BatchRunAction getOwner() {
        return parent;
    }

    public String getBuildStatusIconClassName() {
      return getIconColor().getIconClassName();
    }

    public String getBuildStatusUrl() {
        return getIconColor().getImage();
    }

    /**
     * Obtains the previous execution record, or null if no such record is available.
     */
    public BatchRun getPrevious() {
        // check siblings
        for (AbstractBuild<?, ?> b = parent.owner; b != null; b = b.getPreviousBuild()) {
            BatchRunAction records = b.getAction(BatchRunAction.class);
            if (records == null) continue;
            for (BatchRun r : records.records) {
                if (r.taskName.equals(taskName)
                        && r.timestamp.compareTo(this.timestamp) < 0) // must be older than this
                    return r;
            }
        }
        return null;
    }

    /**
     * Obtains the next execution record, or null if no such record is available.
     */
    public BatchRun getNext() {
        // check siblings
        for (AbstractBuild<?, ?> b = parent.owner; b != null; b = b.getNextBuild()) {
            BatchRunAction records = b.getAction(BatchRunAction.class);
            if (records == null) continue;
            for (BatchRun r : Iterators.reverse(records.records)) {
                if (r.taskName.equals(taskName)
                        && r.timestamp.compareTo(this.timestamp) > 0) // must be newer than this
                    return r;
            }
        }
        return null;
    }

    /**
     * Gets the URL (under the context root) that points to this record.
     *
     * @return URL like "job/foo/53/batchTasks/0"
     */
    public String getUrl() {
        return parent.owner.getUrl() + "batchTasks/" + id;
    }

    public String getSearchUrl() {
        return getUrl();
    }

    public String getDisplayName() {
        return taskName + ' ' + getBuildNumber();
    }

    public String getNumber() {
        return "" + parent.owner.getNumber() + "-" + id;
    }

    public String getBuildNumber() {
        return "#" + getNumber();
    }

    /**
     * Gets the string that says how long the build took to run.
     */
    public String getDurationString() {
        if (isRunning())
            return Util.getTimeSpanString(System.currentTimeMillis() - timestamp.getTimeInMillis()) + " and counting";
        return Util.getTimeSpanString(duration);
    }

    /**
     * Gets the millisecond it took to build.
     */
    @Exported
    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
        run.setDuration(duration);
    }

    void setParent(BatchRunAction parent) {
        this.parent = parent;
        // this.run = RunAdapter.from(this);
    }

    Object readResolve() {
        // this.run = RunAdapter.from(this);
        //this.result = run.getResult();
        return this;
    }
    public void run() {
        StreamBuildListener listener = null;
        try {
            long start = System.currentTimeMillis();
            listener = new StreamBuildListener(new FileOutputStream(getLogFile()));
            Executor executor = Executor.currentExecutor();
            if (executor == null)
                throw new AbortException("ERROR: no executor");
            Node node = executor.getOwner().getNode();
            if (node == null)
                throw new AbortException("ERROR: no node present");

            Launcher launcher = node.createLauncher(listener);

            BatchTask task = getParent();
            if (task == null)
                throw new AbortException("ERROR: undefined task \"" + taskName + "\"");
            AbstractBuild<?, ?> lb = task.owner.getLastBuild();
            if (lb == null)
                throw new AbortException("ERROR: task \"" + taskName + "\" doesn't have a last build");
            FilePath ws = lb.getWorkspace();
            if (ws == null)
                throw new AbortException(lb.getFullDisplayName() + " doesn't have a workspace.");

            try {

                EnvVarsResolver envVarsResolver = new EnvVarsResolver();
                final ArrayList<Environment> buildEnvironments = new ArrayList<Environment>();
                buildEnvironments.add(Environment.create(new EnvVars(envVarsResolver.getEnVars(lb))));


                // This is the only way I found to inject things into the environment of
                // CommandInterpreter.perform().. temporarily attach an action to the build.
                // (if BatchTask/BatchRun are converted to extend AbstractProject/AbstractBuild,
                //  BatchRun will use AbstractRunner and get global/node properties w/o extra code)
                EnvironmentContributingAction envAct = new EnvironmentContributingAction() {
                    public void buildEnvVars(AbstractBuild<?, ?> build, EnvVars env) {
                        // Apply global and node properties
                        for (Environment e : buildEnvironments) e.buildEnvVars(env);
                        // Our task id
                        env.put("TASK_ID", getNumber());
                        // User who triggered this task run, if applicable
                        out:
                        for (CauseAction ca : getActions(CauseAction.class))
                            for (Cause c : ca.getCauses())
                                if (c instanceof Cause.UserCause) {
                                    env.put("HUDSON_USER", ((Cause.UserCause) c).getUserName());
                                    break out;
                                }
                    }

                    public String getDisplayName() {
                        return null;
                    }

                    public String getIconFileName() {
                        return null;
                    }

                    public String getUrlName() {
                        return null;
                    }
                };

                CommandInterpreter batchRunner;

                if (launcher.isUnix())
                    batchRunner = new Shell(task.script);
                else
                    batchRunner = new BatchFile(task.script);
                Lease wsLease = null;
                try {
                    // Lock the workspace
                    Node builtOn = lb.getBuiltOn();
                    if (builtOn == null)
                        throw new AbortException("ERROR: no node for last build");
                    Computer computer = builtOn.toComputer();
                    if (computer == null)
                        throw new AbortException("ERROR: no computer");

                    wsLease = computer.getWorkspaceList().acquire(ws,
                            !task.owner.isConcurrentBuild());
                    // Add environment to build so it will apply when task runs
                    lb.getActions().add(envAct);
                    // Run the task
                    result = batchRunner.perform(lb, launcher, listener) ? Result.SUCCESS : Result.FAILURE;
                } finally {
                    if (wsLease != null) wsLease.release();
                    lb.getActions().remove(envAct);
                    for (Environment e : buildEnvironments) e.tearDown(lb, listener);
                }
            } catch (InterruptedException e) {
                listener.getLogger().println("ABORTED");
                result = Result.ABORTED;
            } catch (EnvInjectException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            }
            setDuration(System.currentTimeMillis() - start);

            // save the build result
            parent.owner.save();
        } catch (AbortException e) {
            result = Result.FAILURE;
            listener.error(e.getMessage());
        } catch (IOException e) {
            result = Result.FAILURE;
            LOGGER.log(Level.SEVERE, "Failed to write " + getLogFile(), e);
        } finally {
            if (listener != null)
                listener.getLogger().close();
            if (result == null)
                result = Result.FAILURE;
        }
    }

   /**
    * Handles incremental log output.
    */
    public void doProgressiveLog(StaplerRequest req, StaplerResponse rsp) throws IOException {
        // FIXME
        // use ? run.getLogText().doProgressiveText(req, rsp);
        new LargeText(getLogFile(), !isRunning()).doProgressText(req, rsp);
    }

    // used by the executors listing
    @Override
    public String toString() {
        String parentName = "not yet resolved";
        if (parent != null && parent.owner != null) {
            parentName = parent.owner.toString();
        }
        return parentName + '-' + id;
    }

    /**
     * Newer records should appear before older records.
     */
    public int compareTo(BatchRun that) {
        return that.timestamp.compareTo(this.timestamp);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BatchRun batchRun = (BatchRun) o;
        return id == batchRun.id && duration == batchRun.duration && Objects.equals(result, batchRun.result) && Objects.equals(timestamp, batchRun.timestamp) && Objects.equals(parent, batchRun.parent) && Objects.equals(taskName, batchRun.taskName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(result, timestamp, parent, id, taskName, duration);
    }

    public long getEstimatedDuration() {
        return getParent().getEstimatedDuration();
    }

    private static final Logger LOGGER = Logger.getLogger(BatchRun.class.getName());
}
