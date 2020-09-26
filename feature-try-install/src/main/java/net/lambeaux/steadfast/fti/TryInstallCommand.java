package net.lambeaux.steadfast.fti;

import org.apache.karaf.features.FeaturesService;
import org.apache.karaf.features.command.completers.AvailableFeatureCompleter;
import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Completion;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
@Command(scope = "feature", name = "tryinstall", description = "Output all missing dependencies")
public class TryInstallCommand implements Action {

  private static final Logger LOGGER = LoggerFactory.getLogger(TryInstallCommand.class);

  @Reference private FeaturesService featuresService;

  @Reference private BundleContext bundleContext;

  @Argument(
    index = 0,
    name = "featureToInstall",
    description =
        "The name and version of the feature to install. A feature id looks like name/version. The version is optional.",
    required = true
    // Only support diagnostics for one feature at a time
    // multiValued = false
  )
  @Completion(AvailableFeatureCompleter.class)
  private String featureToInstall;

  @Override
  public Object execute() throws Exception {
    LOGGER.debug("[Command] Executing FTI command for '{}'", featureToInstall);
    DependencyProvider provider = new DependencyProvider(bundleContext);
    boolean exceptionFreeInstall = false;
    while (!exceptionFreeInstall) {
      try {
        featuresService.installFeature(featureToInstall);
        LOGGER.debug("[Install Attempt] Setting success flag");
        exceptionFreeInstall = true;
      } catch (Exception e) {
        LOGGER.debug("[Install Attempt] Dependencies are still missing");
        provider.resolveMissingDependency(e);
      }
    }
    return null;
  }
}
