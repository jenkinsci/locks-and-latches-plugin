/*
 * The MIT License
 *
 * Copyright (c) 2007-2011, Stephen Connolly, Alan Harder, Romain Seguy
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;
import org.apache.commons.collections.Closure;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.Predicate;
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

    /**
     * @see ResourceActivity#getResourceList()
     */
    public ResourceList getResourceList() {
        ResourceList resources = new ResourceList();
        for (LockWaitConfig lock : locks) {
        	Resource resource = new Resource(null, "locks-and-latches/lock/" + lock.getName(), DESCRIPTOR.getWriteLockCount());
        	if (lock.isShared()) {
        	    resources.r(resource);
        	} else {
        	    resources.w(resource);
        	}
        }
        return resources;
    }

    @Override
    public Environment setUp(AbstractBuild abstractBuild, Launcher launcher, BuildListener buildListener) throws IOException, InterruptedException {
        final List<NamedReentrantLock> backups = new ArrayList<NamedReentrantLock>();
        List<LockWaitConfig> locks = new ArrayList<LockWaitConfig>(this.locks);

        // sort this list of locks so that we _always_ ask for the locks in order
        Collections.sort(locks, new Comparator<LockWaitConfig>() {
            public int compare(LockWaitConfig o1, LockWaitConfig o2) {
                return o1.getName().compareTo(o2.getName());
            }
        });

        // build the list of "real" locks
        final Map<String,Boolean> sharedLocks = new HashMap<String,Boolean>();
        for (LockWaitConfig lock : locks) {
            NamedReentrantLock backupLock;
            do {
                backupLock = DESCRIPTOR.backupLocks.get(lock.getName());
                if (backupLock == null) {
                    DESCRIPTOR.backupLocks.putIfAbsent(lock.getName(), new NamedReentrantLock(lock.getName()));
                }
            } while (backupLock == null);
            backups.add(backupLock);
            sharedLocks.put(lock.getName(), lock.isShared());
        }

        final StringBuilder locksToGet = new StringBuilder();
        CollectionUtils.forAllDo(backups, new Closure() {
            public void execute(Object input) {
                locksToGet.append(((NamedReentrantLock) input).getName()).append(", ");
            }
        });

        buildListener.getLogger().println("[locks-and-latches] Locks to get: " + locksToGet.substring(0, locksToGet.length()-2));

        boolean haveAll = false;
        while (!haveAll) {
            haveAll = true;
            List<NamedReentrantLock> locked = new ArrayList<NamedReentrantLock>();

            DESCRIPTOR.lockingLock.lock();
            try {
                for (NamedReentrantLock lock : backups) {
                	boolean shared = sharedLocks.get(lock.getName());
                	buildListener.getLogger().print("[locks-and-latches] Trying to get " + lock.getName() + " in " + (shared ? "shared" : "exclusive") + " mode... ");
                	Lock actualLock;
                	if (shared) {
                	    actualLock = lock.readLock();
                	} else {
                	    actualLock = lock.writeLock();
                	}
                	if (actualLock.tryLock()) {
                        buildListener.getLogger().println(" Success");
                        locked.add(lock);
                    } else {
                        buildListener.getLogger().println(" Failed, releasing all locks");
                        haveAll = false;
                        break;
                    }
                }
                if (!haveAll) {
                    // release them all
                	for (NamedReentrantLock lock : locked) {
                	    boolean shared = sharedLocks.get(lock.getName());
                	    Lock actualLock;
                	    if (shared) {
                	        actualLock = lock.readLock();
                	    } else {
                	        actualLock = lock.writeLock();
                	    }
                	    actualLock.unlock();
                	}
                }
            } finally {
                DESCRIPTOR.lockingLock.unlock();
            }
            
            if (!haveAll) {
                buildListener.getLogger().println("[locks-and-latches] Could not get all the locks, sleeping for 1 minute...");
                TimeUnit.SECONDS.sleep(60);
            }
        }

        buildListener.getLogger().println("[locks-and-latches] Have all the locks, build can start");

        return new Environment() {
            @Override
            public boolean tearDown(AbstractBuild abstractBuild, BuildListener buildListener) throws IOException, InterruptedException {
                buildListener.getLogger().println("[locks-and-latches] Releasing all the locks");
                for (NamedReentrantLock lock : backups) {
                    boolean shared = sharedLocks.get(lock.getName());
                    Lock actualLock;
                    if (shared) {
                        actualLock = lock.readLock();
                    } else {
                        actualLock = lock.writeLock();
                    }
                    actualLock.unlock();
                }
                buildListener.getLogger().println("[locks-and-latches] All the locks released");
                return super.tearDown(abstractBuild, buildListener);
            }
        };
    }

    public void makeBuildVariables(AbstractBuild build, Map<String,String> variables) {
        final StringBuilder names = new StringBuilder();
        for (LockWaitConfig lock : locks) {
            if (names.length() > 0) {
                names.append(',');
            }
            names.append(lock.getName());
        }
        variables.put("LOCKS", names.toString());
    }

    public String getDisplayName() {
        return DESCRIPTOR.getDisplayName();
    }

    public static final class DescriptorImpl extends Descriptor<BuildWrapper> {
        private List<LockConfig> locks;

        /**
         * Required to work around HUDSON-2450.
         */
        private transient ConcurrentMap<String, NamedReentrantLock> backupLocks =
                new ConcurrentHashMap<String, NamedReentrantLock>();

        /**
         * Used to guarantee exclusivity when a build tries to get all its locks.
         */
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
            CollectionUtils.filter(getLocks(), new Predicate() {
                public boolean evaluate(Object object) {
                    return StringUtils.isNotBlank(((LockConfig) object).getName());
                }
            });
            
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
        private boolean shared;
        private transient LockConfig lock;

        public LockWaitConfig() {
        }

        @DataBoundConstructor
        public LockWaitConfig(String name, boolean shared) {
            this.name = name;
            this.shared = shared;
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
        
        public boolean isShared() {
            return shared;
        }

        public void setShared(boolean shared) {
            this.shared = shared;
        }

    }

    /**
     * Extends {@code ReentrantLock} to add a {@link #name} attribute (mainly
     * for display purposes).
     */
    public static final class NamedReentrantLock extends ReentrantReadWriteLock {
        private String name;

        public NamedReentrantLock(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    private static final Logger LOGGER = Logger.getLogger(LockWrapper.class.getName());
    
}
