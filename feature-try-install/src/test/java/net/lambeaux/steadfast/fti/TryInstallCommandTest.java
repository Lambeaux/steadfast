package net.lambeaux.steadfast.fti;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TryInstallCommandTest {

  private static final String JAR_OUTPUT_PATH = "target/";

  private static final Map<String, String> MANIFEST_PROPS =
      ImmutableMap.<String, String>builder()
          .put("Manifest-Version", "1.0")
          .put("Build-Jdk", System.getProperty("java.version"))
          .put("Built-By", "feature-try-install")
          .put("Bundle-Description", "Pretends to provide dependencies")
          //          .put("Bundle-DocURL", "http://connexta.com")
          .put("Bundle-ManifestVersion", "2")
          .put("Bundle-Name", "Mock Package Provider")
          .put("Bundle-SymbolicName", "mock-package-provider")
          //          .put("Bundle-Vendor", "vendor")
          .put("Bundle-Version", "0.0-SNAPSHOT")
          .put("Created-By", "feature-try-install")
          .build();

  @Test
  public void execute() throws IOException {
    makeJar(
        "mock.jar",
        ImmutableMap.<String, String>builder()
            .putAll(MANIFEST_PROPS)
            .put("Fti-LastModified", String.valueOf(System.currentTimeMillis()))
            .build());
    assertTrue(true);
  }

  // Example match
  // filter:="(&(osgi.wiring.package=org.apache.commons.lang)(version>=2.6.0)(!(version>=3.0.0)))"
  private static final Pattern OSGI_PACKAGE_FILTER =
      Pattern.compile(
          "filter:=\"\\(&"
              + "\\(osgi.wiring.package=[a-zA-Z]+(\\.[a-zA-Z0-9_]+)+\\)"
              + "\\(version>=[0-9]+(\\.[0-9]+)+\\)"
              + "\\(!\\(version>=[0-9]+(\\.[0-9]+)+\\)\\)"
              + "\\)\"");

  private static final String ERROR_LINE =
      "Unable to resolve root: missing requirement [root] osgi.identity;"
          + " osgi.identity=test-io;"
          + " type=karaf.feature;"
          + " version=\"[2.19.11,2.19.11]\";"
          + " filter:=\"(&(osgi.identity=test-io)(type=karaf.feature)(version>=2.19.11)(version<=2.19.11))\""
          + " [caused by: Unable to resolve test-io/2.19.11: missing requirement [test-io/2.19.11] osgi.identity;"
          + " osgi.identity=platform-io-impl;"
          + " type=osgi.bundle;"
          + " version=\"[2.19.11,2.19.11]\";"
          + " resolution:=mandatory"
          + " [caused by: Unable to resolve platform-io-impl/2.19.11: missing requirement [platform-io-impl/2.19.11] osgi.wiring.package;"
          + " filter:=\"(&(osgi.wiring.package=org.apache.commons.lang)(version>=2.6.0)(!(version>=3.0.0)))\"]]";

  @Test
  public void verifyRegexAndSplittingAssumptions() {
    String keyValueLine = "(osgi.wiring.package=org.apache.commons.lang)";
    String value = keyValueLine.split("=")[1];
    String pkg = value.substring(0, value.length() - 1);
    assertEquals("org.apache.commons.lang", pkg);

    Matcher matcher = OSGI_PACKAGE_FILTER.matcher(ERROR_LINE);
    matcher.find();
    System.out.println(matcher.group());
    System.out.println("---");

    String matched =
        "filter:=\"(&(osgi.wiring.package=org.apache.commons.lang)(version>=2.6.0)(!(version>=3.0.0)))\"";
    String tmp = matched.substring(12, matched.length() - 3);
    String[] kvpairs = tmp.split("\\)\\(");
    String pName = kvpairs[0].split("=")[1];
    String pVers = kvpairs[1].split(">=")[1];

    System.out.println(Arrays.toString(kvpairs));
    System.out.println(pName);
    System.out.println(pVers);
  }

  @Test
  public void verifySetAssumptions() {
    Set<Object> stuff =
        ImmutableSet.builder()
            .add(new AbstractMap.SimpleEntry<>("com.ex", "1.0"))
            .add(new AbstractMap.SimpleEntry<>("com.te", "1.0"))
            .add(new AbstractMap.SimpleEntry<>("com.ex", "2.0"))
            .add(new AbstractMap.SimpleEntry<>("com.te", "2.0"))
            .build();

    assertEquals(4, stuff.size());

    assertTrue(stuff.contains(new AbstractMap.SimpleEntry<>("com.ex", "1.0")));
    assertTrue(stuff.contains(new AbstractMap.SimpleEntry<>("com.te", "1.0")));
    assertTrue(stuff.contains(new AbstractMap.SimpleEntry<>("com.ex", "2.0")));
    assertTrue(stuff.contains(new AbstractMap.SimpleEntry<>("com.te", "2.0")));

    assertFalse(stuff.contains(new AbstractMap.SimpleEntry<>("com.na", "1.0")));
    assertFalse(stuff.contains(new AbstractMap.SimpleEntry<>("", "")));
  }

  private static void makeJar(String jarName, Map<String, String> manifestMap) throws IOException {
    Manifest manifest = new Manifest();
    manifestMap.forEach((key, val) -> manifest.getMainAttributes().putValue(key, val));

    JarOutputStream target =
        new JarOutputStream(new FileOutputStream(JAR_OUTPUT_PATH + jarName), manifest);
    target.close();
  }
}
