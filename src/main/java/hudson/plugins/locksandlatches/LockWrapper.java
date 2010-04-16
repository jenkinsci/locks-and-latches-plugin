package hudson.plugins.locksandlatches;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildWrapper;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import org.apache.commons.lang.StringUtils;

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

    @Override
    public Descriptor<BuildWrapper> getDescriptor() {
        return DESCRIPTOR;
    }

    @Extension
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
        final List<ReentrantLock> backups = new ArrayList<ReentrantLock>();
        List<LockWaitConfig> locks = new ArrayList<LockWaitConfig>(this.locks);
        // sort this list of locks so that we _always_ ask for the locks in order
        Collections.sort(locks, new Comparator<LockWaitConfig>() {
            public int compare(LockWaitConfig o1, LockWaitConfig o2) {
                return o1.getName().compareTo(o2.getName());
            }
        });
        for (LockWaitConfig lock : locks) {
            ReentrantLock backupLock;
            do {
                backupLock = DESCRIPTOR.backupLocks.get(lock.getName());
                if (backupLock == null) {
                    DESCRIPTOR.backupLocks.putIfAbsent(lock.getName(), new ReentrantLock());
                }
            } while (backupLock == null);
            backups.add(backupLock);
        }
        buildListener.getLogger().println("[locks-and-latches] Checking to see if we really have the locks");
        boolean haveAll = false;
        while (!haveAll) {
            haveAll = true;
            List<ReentrantLock> locked = new ArrayList<ReentrantLock>();
            DESCRIPTOR.lockingLock.lock();
            try {
                for (ReentrantLock lock : backups) {
                    if (lock.tryLock()) {
                        locked.add(lock);
                    } else {
                        haveAll = false;
                        break;
                    }
                }
                if (!haveAll) {
                    // release them all
                    for (ReentrantLock lock : locked) {
                        lock.unlock();
                    }
                }
            } finally {
                DESCRIPTOR.lockingLock.unlock();
            }
            if (!haveAll) {
                buildListener.getLogger().println("[locks-and-latches] Could not get all the locks... sleeping for 1 minute");
                TimeUnit.SECONDS.sleep(60);
            }
        }
        buildListener.getLogger().println("[locks-and-latches] Have all the locks, build can start");

        return new Environment() {
            @Override
            public boolean tearDown(AbstractBuild abstractBuild, BuildListener buildListener) throws IOException, InterruptedException {
                buildListener.getLogger().println("[locks-and-latches] Releasing all the locks");
                for (ReentrantLock lock : backups) {
                    lock.unlock();
                }
                buildListener.getLogger().println("[locks-and-latches] All the locks released");
                return super.tearDown(abstractBuild, buildListener);
            }
        };
    }

    public String getDisplayName() {
        return DESCRIPTOR.getDisplayName();
    }

    public static final class DescriptorImpl extends Descriptor<BuildWrapper> {
        private List<LockConfig> locks;

        /**
         * required to work around https://hudson.dev.java.net/issues/show_bug.cgi?id=2450
         */
        private transient ConcurrentMap<String, ReentrantLock> backupLocks =
                new ConcurrentHashMap<String, ReentrantLock>();

        private transient ReentrantLock lockingLock = new ReentrantLock();

        DescriptorImpl() {
            super(LockWrapper.class);
            load();
        }

        public String getDisplayName() {
            return "Locks";
        }


        @Override
        public BuildWrapper newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            List<LockWaitConfig> locks = req.bindParametersToList(LockWaitConfig.class, "locks.locks.");
            return new LockWrapper(locks);
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            req.bindParameters(this, "locks.");
            locks = req.bindParametersToList(LockConfig.class, "locks.lock.");
            save();
            return super.configure(req, formData);
        }

        @Override
        public synchronized void save() {
            // let's remove blank locks
            List<LockConfig> blankLocks = new ArrayList<LockConfig>();
            for(LockConfig lock: getLocks()) {
                if(StringUtils.isBlank(lock.getName())) {
                    blankLocks.add(lock);
                }
            }
            locks.removeAll(blankLocks);

            // now, we can safely sort remaining locks
            Collections.sort(this.locks, new Comparator<LockConfig>() {
                public int compare(LockConfig lock1, LockConfig lock2) {
                    return lock1.getName().compareToIgnoreCase(lock2.getName());
                }
            });
            
            super.save();
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

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            LockConfig that = (LockConfig) o;

            if (name != null ? !name.equals(that.name) : that.name != null) return false;

            return true;
        }

        @Override
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
