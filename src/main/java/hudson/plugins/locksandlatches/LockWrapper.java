package hudson.plugins.locksandlatches;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.Build;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.Resource;
import hudson.model.ResourceActivity;
import hudson.model.ResourceList;
import hudson.tasks.BuildWrapper;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Created by IntelliJ IDEA.
 *
 * @author Stephen Connolly
 * @since 26-Sep-2007 16:06:28
 */
public class LockWrapper extends BuildWrapper implements ResourceActivity {
    private List<LockWaitConfig> locks;

    public LockWrapper(List<LockWaitConfig> locks) {
        this.locks = locks;
    }

    public List<LockWaitConfig> getLocks() {
        return locks;
    }

    public void setLocks(List<LockWaitConfig> locks) {
        this.locks = locks;
    }

    public Descriptor<BuildWrapper> getDescriptor() {
        return DESCRIPTOR;
    }

    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public ResourceList getResourceList() {
        ResourceList resources = new ResourceList();
        for (LockWaitConfig lock : locks) {
            resources.w(new Resource(null, "locks-and-latches/lock/" + lock.getName(), DESCRIPTOR.getWriteLockCount()));
        }
        return resources;
    }


    @Override
    public Environment setUp(AbstractBuild abstractBuild, Launcher launcher, BuildListener buildListener) throws IOException, InterruptedException {
        return new Environment() {
        };
    }

    @Override
    public Environment setUp(Build build, Launcher launcher, BuildListener buildListener) throws IOException, InterruptedException {
        return setUp((AbstractBuild) build, launcher, buildListener);
    }

    public String getDisplayName() {
        return DESCRIPTOR.getDisplayName();
    }

    public static final class DescriptorImpl extends Descriptor<BuildWrapper> {
        private List<LockConfig> locks;

        DescriptorImpl() {
            super(LockWrapper.class);
            load();
        }

        public String getDisplayName() {
            return "Locks";
        }


        @Override
        public BuildWrapper newInstance(StaplerRequest req) throws FormException {
            List<LockWaitConfig> locks = req.bindParametersToList(LockWaitConfig.class, "locks.locks.");
            return new LockWrapper(locks);
        }

        public boolean configure(StaplerRequest req) throws FormException {
            req.bindParameters(this, "locks.");
            locks = req.bindParametersToList(LockConfig.class, "locks.lock.");
            save();
            return super.configure(req);
        }

        public List<LockConfig> getLocks() {
            if (locks == null) {
                locks = new ArrayList<LockConfig>();
                // provide default if we have none
                locks.add(new LockConfig("(default)"));
            }
            return locks;
        }

        public void setLocks(List<LockConfig> locks) {
            this.locks = locks;
        }

        public LockConfig getLock(String name) {
            for (LockConfig host : locks) {
                if (name.equals(host.getName())) {
                    return host;
                }
            }
            return null;
        }

        public String[] getLockNames() {
            getLocks();
            String[] result = new String[locks.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = locks.get(i).getName();
            }
            return result;
        }

        public void addLock(LockConfig hostConfig) {
            locks.add(hostConfig);
            save();
        }

        /**
         * There wass a bug in the ResourceList.isCollidingWith,
         * this method used to determine the hack workaround if the bug is not fixed, but now only needs to
         * return 1.
         */
        synchronized int getWriteLockCount() {
            return 1;
        }
    }

    public static final class LockConfig implements Serializable {
        private String name;
        private transient AbstractBuild owner = null;

        public LockConfig() {
        }

        @DataBoundConstructor
        public LockConfig(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            LockConfig that = (LockConfig) o;

            if (name != null ? !name.equals(that.name) : that.name != null) return false;

            return true;
        }

        public int hashCode() {
            int result;
            result = (name != null ? name.hashCode() : 0);
            return result;
        }
    }

    public static final class LockWaitConfig implements Serializable {
        private String name;
        private transient LockConfig lock;

        public LockWaitConfig() {
        }

        @DataBoundConstructor
        public LockWaitConfig(String name) {
            this.name = name;
        }

        public LockConfig getLock() {
            if (lock == null && name != null && !"".equals(name)) {
                setLock(DESCRIPTOR.getLock(name));
            }
            return lock;
        }

        public void setLock(LockConfig lock) {
            this.lock = lock;
        }

        public String getName() {
            if (lock == null) {
                return name;
            }
            return name = lock.getName();
        }

        public void setName(String name) {
            setLock(DESCRIPTOR.getLock(this.name = name));
        }

    }

    private static final Logger LOGGER = Logger.getLogger(LockWrapper.class.getName());
}
