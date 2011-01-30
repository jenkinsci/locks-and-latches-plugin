/*
 * The MIT License
 *
 * Copyright (c) 2007-2011, Stephen Connolly, Alan Harder
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
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.tasks.BuildWrapper;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

/**
 * Created by IntelliJ IDEA.
 *
 * @author connollys
 * @since 04-Dec-2007 12:04:47
 */
public class LatchWrapper extends BuildWrapper {
    @Override
    public Environment setUp(AbstractBuild abstractBuild, Launcher launcher, BuildListener buildListener) throws IOException, InterruptedException {
        return new Environment() {
        };
    }

    public String getDisplayName() {
        return DESCRIPTOR.getDisplayName();
    }

    public static final class DescriptorImpl extends Descriptor<BuildWrapper> {
        private List<LatchConfig> latches;
        private static transient ConcurrentMap<LatchConfig, Set<AbstractBuild>> waitingJobs =
                new ConcurrentHashMap<LatchConfig,Set<AbstractBuild>>();
        private static transient ConcurrentMap<LatchConfig, Boolean> releasedLatches =
                new ConcurrentHashMap<LatchConfig,Boolean>();

        public void register(AbstractBuild build, Set<LatchConfig> latches) {

        }

        DescriptorImpl() {
            super(LatchWrapper.class);
            load();
        }

        public String getDisplayName() {
            return "Latches";
        }

        @Override
        public BuildWrapper newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            List<LatchWaitConfig> latches = req.bindParametersToList(LatchWaitConfig.class, "latches.latches.");
            return new LatchWrapper(); //TODO
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            req.bindParameters(this, "latches.");
            latches = req.bindParametersToList(LatchConfig.class, "latches.latch.");
            save();
            return super.configure(req, formData);
        }

        public List<LatchConfig> getLatches() {
            if (latches == null) {
                latches = new ArrayList<LatchConfig>();
                // provide default if we have none
                latches.add(new LatchConfig("(default)", 1, 360));
            }
            return latches;
        }

        public void setLatches(List<LatchConfig> latches) {
            this.latches = latches;
        }

        public LatchConfig getLatch(String name) {
            getLatches();
            for (LatchConfig latchConfig : latches) {
                if (name.equals(latchConfig.getName())) {
                    return latchConfig;
                }
            }
            return null;
        }

        public String[] getLatchNames() {
            String[] result = new String[latches.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = latches.get(i).getName();
            }
            return result;
        }

        public void addLatch(LatchConfig latch) {
            latches.add(latch);
            save();
        }

    }

    @Override
    public Descriptor<BuildWrapper> getDescriptor() {
        return DESCRIPTOR;
    }

    // @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static final class LatchConfig implements Serializable {
        private String name;
        private int count;
        private long timeout;

        public LatchConfig() {
            this.count = 1;
        }

        @DataBoundConstructor
        public LatchConfig(String name, int count, long timeout) {
            this.name = name;
            this.count = count;
            this.timeout = timeout;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public long getTimeout() {
            return timeout;
        }

        public void setTimeout(long timeout) {
            this.timeout = timeout;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            LatchConfig that = (LatchConfig) o;

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

    public static final class LatchWaitConfig implements Serializable {
        private String name;
        private transient LatchConfig latch;

        public LatchWaitConfig() {
        }

        @DataBoundConstructor
        public LatchWaitConfig(String name) {
            this.name = name;
        }

        public LatchConfig getLatch() {
            if (latch == null && name != null && !"".equals(name)) {
                setLatch(DESCRIPTOR.getLatch(name));
            }
            return latch;
        }

        public void setLatch(LatchConfig latch) {
            this.latch = latch;
        }

        public String getName() {
            if (latch == null) {
                return name;
            }
            return name = latch.getName();
        }

        public void setName(String name) {
            setLatch(DESCRIPTOR.getLatch(this.name = name));
        }

    }

    private static final Logger LOGGER = Logger.getLogger(LatchWrapper.class.getName());
}

