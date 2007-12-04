package hudson.plugins.locksandlatches;

import hudson.Plugin;
import hudson.util.FormFieldValidator;
import hudson.tasks.BuildStep;
import hudson.tasks.BuildWrappers;
import org.apache.commons.beanutils.ConvertUtils;
import org.apache.commons.beanutils.Converter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.net.URL;
import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Entry point of locks-and-latches plugin.
 *
 * @author Stephen Connolly
 * @plugin
 */
public class PluginImpl extends Plugin {
    private final String URL_PREFIX = "file:/";

    public void start() throws Exception {
        BuildWrappers.WRAPPERS.add(LockWrapper.DESCRIPTOR);
        //BuildWrappers.WRAPPERS.add(LatchWrapper.DESCRIPTOR);
    }

    private static final java.util.logging.Logger LOGGER = Logger.getLogger(PluginImpl.class.getName());
}
