package net.lambeaux.steadfast.fti;

import com.google.common.collect.ImmutableMap;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.FailsafeException;
import net.jodah.failsafe.RetryPolicy;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A provider of dependencies for OSGi resolution purposes. Currently, just manages a bundle that
 * can provide package exports to the Karaf container without any class files.
 *
 * <p>Pretending to satisfy bundle dependencies will allow feature resolution to continue after the
 * previous failure at the cost of rendering the installed feature completely unusable.
 *
 * <p>The following is a sample exception string from a failed feature install, which is normally
 * one line but has been formatted for clarity.
 *
 * <pre>{@code
 * Unable to resolve root:
 *   missing requirement [root] osgi.identity;
 *     osgi.identity=test-io;
 *     type=karaf.feature;
 *     version="[2.19.11,2.19.11]";
 *     filter:="(&(osgi.identity=test-io)(type=karaf.feature)(version>=2.19.11)(version<=2.19.11))"
 * [caused by: Unable to resolve test-io/2.19.11:
 *   missing requirement [test-io/2.19.11] osgi.identity;
 *     osgi.identity=platform-io-impl;
 *     type=osgi.bundle;
 *     version="[2.19.11,2.19.11]";
 *     resolution:=mandatory
 * [caused by: Unable to resolve platform-io-impl/2.19.11:
 *   missing requirement [platform-io-impl/2.19.11] osgi.wiring.package;
 *     filter:="(&(osgi.wiring.package=org.apache.commons.lang)(version>=2.6.0)(!(version>=3.0.0)))"]]
 * }</pre>
 *
 * <p>The following are sample manifest export package statements. There are two instances of one
 * line that have been formatted for clarity.
 *
 * <pre>{@code
 * Export-Package: this.package.exported;also.this.one;version="2.24.0",and.this.one;
 * Export-Package: org.codice.ddf.catalog.core.plugin.metacarddeduplication;
 *   uses:="ddf.catalog,ddf.catalog.data,ddf.catalog.filter,ddf.catalog.operation,ddf.catalog.plugin";
 *   version="16.0.0"
 * }</pre>
 */
public class DependencyProvider {

  private static final Logger LOGGER = LoggerFactory.getLogger(DependencyProvider.class);

  private static final String JDK_VERSION = System.getProperty("java.version");

  private static final String JAR_NAME = "mock.jar";

  private static final Map<String, String> DEFAULT_MANIFEST_ATTRS =
      ImmutableMap.<String, String>builder()
          .put("Manifest-Version", "1.0")
          .put("Build-Jdk", JDK_VERSION)
          .put("Built-By", "feature-try-install")
          .put("Created-By", "feature-try-install")
          .put("Bundle-Name", "Dependency Provider")
          .put("Bundle-SymbolicName", "dependency-provider")
          .put("Bundle-Description", "Pretends to provide dependencies")
          .put("Bundle-ManifestVersion", "2")
          .put("Bundle-Version", "0")
          .build();

  private static final String EXPORT_TEMPLATE = "%s;version=\"%s\"";

  // Example match
  // filter:="(&(osgi.wiring.package=org.apache.commons.lang)(version>=2.6.0)(!(version>=3.0.0)))"
  private static final Pattern OSGI_PACKAGE_FILTER =
      Pattern.compile(
          "filter:=\"\\(&"
              + "\\(osgi.wiring.package=[a-zA-Z]+(\\.[a-zA-Z0-9_]+)+\\)"
              + "\\(version>=[0-9]+(\\.[0-9]+)+\\)"
              + "\\(!\\(version>=[0-9]+(\\.[0-9]+)+\\)\\)"
              + "\\)\"");

  private final BundleContext bundleContext;

  private final Path jarPath;

  private final List<Map.Entry<String, String>> exports;

  private static String getKarafHome() {
    final String karafHome = System.getProperty("karaf.home");
    if (karafHome == null || karafHome.isEmpty()) {
      throw new IllegalStateException("System property 'karaf.home' has not been set");
    }
    LOGGER.debug("karaf.home = '{}'", karafHome);
    return karafHome;
  }

  public DependencyProvider(BundleContext bundleContext) throws BundleException, IOException {
    this(
        bundleContext,
        Paths.get(getKarafHome())
            .resolve("data")
            .resolve("tmp")
            .resolve("tryinstall")
            .toAbsolutePath());
  }

  public DependencyProvider(BundleContext bundleContext, Path jarDirPath)
      throws BundleException, IOException {
    this.bundleContext = bundleContext;
    this.jarPath = jarDirPath.resolve(JAR_NAME);
    this.exports = new ArrayList<>();

    if (jarDirPath.toFile().exists()) {
      LOGGER.debug("Clearing workspace '{}'", jarDirPath);
      Files.walk(jarDirPath)
          .sorted(Comparator.reverseOrder())
          .forEach(
              f -> {
                try {
                  Files.delete(f);
                } catch (IOException e) {
                  throw new TryInstallException(e);
                }
              });
    }

    LOGGER.debug("Setting up workspace '{}'", jarDirPath);
    Files.createDirectories(jarDirPath);

    LOGGER.debug("Setting up artifacts & dependencies");
    writeJar(jarPath.toString(), Collections.emptyMap());
    initBundleForJar(bundleContext, jarPath);
  }

  /**
   * Attempts to resolve the missing dependency as specified by {@code e}. The managed jar's
   * manifest will be updated to claim it provides the missing package so that dependency resolution
   * may proceed.
   *
   * @param e an exception thrown by {@link
   *     org.apache.karaf.features.FeaturesService#installFeature(String)} or any overloaded
   *     counterpart.
   */
  public void resolveMissingDependency(Exception e) throws BundleException, IOException {
    final String failureMessage = e.getMessage();
    final Matcher packageFilterMatcher = OSGI_PACKAGE_FILTER.matcher(failureMessage);
    final boolean foundPackageFilter = packageFilterMatcher.find();

    if (!foundPackageFilter) {
      throw new TryInstallException(
          "Could not find filter string for missing package within exception message", e);
    }

    final Map.Entry<String, String> pair = extractPackage(packageFilterMatcher.group());
    if (exports.contains(pair)) {
      throw new TryInstallException(
          String.format(
              "Exporting package %s/%s did not satisfy the dependency",
              pair.getKey(), pair.getValue()),
          e);
    }

    LOGGER.debug("Providing package dependency {}/{}", pair.getKey(), pair.getValue());
    exports.add(pair);

    writeJar(
        jarPath.toString(),
        ImmutableMap.of(
            "Export-Package",
            exports
                .stream()
                .map(entry -> String.format(EXPORT_TEMPLATE, entry.getKey(), entry.getValue()))
                .collect(Collectors.joining(","))));

    refreshBundle(bundleContext, jarPath);
  }

  /** Keep in alignment with {@link #OSGI_PACKAGE_FILTER}. */
  private static Map.Entry<String, String> extractPackage(String matched) {
    String tmp = matched.substring(12, matched.length() - 3);
    String[] kvpairs = tmp.split("\\)\\(");
    String pName = kvpairs[0].split("=")[1];
    String pVers = kvpairs[1].split(">=")[1];
    return new AbstractMap.SimpleEntry<>(pName, pVers);
  }

  /**
   * Writes out a jar file to {@code path}, overwriting any data that already exists. Manifest
   * attributes in {@code extraAttributes} cannot duplicate any keys from {@link
   * #DEFAULT_MANIFEST_ATTRS} or the write will fail.
   */
  private static void writeJar(String path, Map<String, String> extraAttributes)
      throws IOException {
    final Map<String, String> allManifestAttributes =
        ImmutableMap.<String, String>builder()
            .putAll(DEFAULT_MANIFEST_ATTRS)
            .putAll(extraAttributes)
            .put("Fti-LastModified", String.valueOf(System.currentTimeMillis()))
            .build();

    final Manifest manifest = new Manifest();
    allManifestAttributes.forEach((key, val) -> manifest.getMainAttributes().putValue(key, val));

    LOGGER.debug("Writing jar '{}'", path);
    final JarOutputStream target = new JarOutputStream(new FileOutputStream(path), manifest);
    target.close();
  }

  /**
   * Initializes and installs an OSGi bundle for the jar at {@code jarPath}. If one already exists,
   * it will be updated to reflect the jar's current contents. The bundle will be active when this
   * function returns.
   */
  private static void initBundleForJar(BundleContext context, Path jarPath) throws BundleException {
    final URI mockJarUri = jarPath.toUri();
    final String mockJarUriString = mockJarUri.toString();
    final Bundle mockJarBundle = context.getBundle(mockJarUriString);

    if (mockJarBundle == null) {
      LOGGER.debug(
          "Mock jar '{}' bundle does not exist; for init, attempting to install", mockJarUriString);
      final Bundle mockJarInstalledBundle = context.installBundle(mockJarUriString);
      mockJarInstalledBundle.start();
      waitForBundleState(
          mockJarInstalledBundle,
          Bundle.ACTIVE,
          "Cannot run 'tryinstall' if mock jar is not installed");
    } else {
      LOGGER.debug(
          "Mock jar '{}' bundle already exists; for init, attempting to update", mockJarUriString);
      refreshBundle(context, jarPath);
    }
  }

  /**
   * Assuming a bundle is installed for {@code jarPath}, updates the bundle to reflect the jar's
   * current contents. The bundle will be active when this function returns.
   */
  private static void refreshBundle(BundleContext context, Path jarPath) throws BundleException {
    final URI mockJarUri = jarPath.toUri();
    final String mockJarUriString = mockJarUri.toString();
    final Bundle mockJarBundle = context.getBundle(mockJarUriString);

    if (mockJarBundle == null) {
      throw new IllegalStateException("Cannot refresh a bundle that has never been installed");
    }

    mockJarBundle.stop();
    waitForBundleState(
        mockJarBundle,
        Bundle.RESOLVED,
        "During a refresh attempt, the bundle was not stopped fast enough");
    mockJarBundle.update();
    waitForBundleState(
        mockJarBundle,
        Bundle.INSTALLED,
        "During a refresh attempt, the bundle was not updated fast enough");
    mockJarBundle.start();
    waitForBundleState(
        mockJarBundle,
        Bundle.ACTIVE,
        "During a refresh attempt, the bundle was not started fast enough");
  }

  /**
   * Waits for the given {@code bundle} to enter the given {@code expectedState}. Throws a {@link
   * TryInstallException} with {@code failureMessage} if left waiting too long.
   */
  private static void waitForBundleState(Bundle bundle, int expectedState, String failureMessage) {
    try {
      final int returnedState =
          Failsafe.with(
                  new RetryPolicy()
                      .<Integer>retryIf((bundleState) -> bundleState != expectedState)
                      .withDelay(100, TimeUnit.MILLISECONDS)
                      .withMaxRetries(20))
              .get(bundle::getState);

      if (returnedState != expectedState) {
        throw new TryInstallException(failureMessage);
      }
    } catch (FailsafeException e) {
      throw new TryInstallException(failureMessage, e);
    }
  }
}
