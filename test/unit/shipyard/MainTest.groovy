package shipyard

import shipyard.command.*
import shipyard.vault.*
import java.util.concurrent.Callable
import groovy.mock.interceptor.MockFor
import org.junit.*

public class MainTest {
  @After
  void after() {
    Main.metaClass = null
    Read.metaClass = null
  }

  @Test
  void testNormalFlow() {
    def props = new Properties()
    def actualExitCode
    props.put("test","value")
    props.put("storageServiceClass", "shipyard.vault.VaultService")
    props.put("vaultUrl", "http://localhost:8200")
    props.put("vaultToken", "123")
    props.put("command", "read")
    props.put("path", "foo")
    def shutdown = false
    Main.metaClass.'static'.loadProperties = { String[] args ->
      return props
    }
    Main.metaClass.'static'.createPIDFile = { String filename -> }
    Main.metaClass.'static'.deletePIDFile = { String filename -> }
    Main.metaClass.'static'.terminate = { exitCode -> actualExitCode = exitCode }
    def called = 0
    def args = new String[1]
    args[0] = "-conf=test.properties"

    Read.metaClass.execute = { StorageService storageSvc, Properties p -> return 0 }
    Main.main(args)

    assert actualExitCode == 0
  }

  @Test
  void testAbortFlow() {
    def props = new Properties()
    def actualExitCode
    props.put("test","value")
    def shutdown = false
    Main.metaClass.'static'.loadProperties = { String[] args ->
      return props
    }
    Main.metaClass.'static'.createPIDFile = { String filename -> }
    Main.metaClass.'static'.deletePIDFile = { String filename -> }
    Main.metaClass.'static'.terminate = { exitCode -> actualExitCode = exitCode }
    def called = 0
    def saMock = new MockFor(SuperintendentAgent)
    saMock.demand.init(1) { }
    saMock.demand.call(1) { called++; throw new Exception() } 
    def args = new String[1]
    args[0] = "-conf=test.properties"
    saMock.use {
      Main.main(args)
    }
    assert called == 1
    assert actualExitCode == 2
  }


  @Test
  void testAbortFlowFromJsonException() {
    def props = new Properties()
    def actualExitCode
    props.put("test","value")
    def shutdown = false
    Main.metaClass.'static'.loadProperties = { String[] args ->
      return props
    }
    Main.metaClass.'static'.createPIDFile = { String filename -> }
    Main.metaClass.'static'.deletePIDFile = { String filename -> }
    Main.metaClass.'static'.terminate = { exitCode -> actualExitCode = exitCode }
    def called = 0
    def saMock = new MockFor(SuperintendentAgent)
    saMock.demand.init(1) { }
    saMock.demand.call(1) { called++; throw new groovy.json.JsonException("sensitive details") } 
    def args = new String[1]
    args[0] = "-conf=test.properties"
    saMock.use {
      Main.log.track { tracker ->
        Main.main(args)
        assert !tracker.isLogged("sensitive details")
        assert tracker.isLogged("JSON error; see console for details")
      }
    }
    assert called == 1
    assert actualExitCode == 2
  }

  @Test
  void testMapPropertiesToSystemProperties() {
    def propName = "unitTestProperty1234"
    def propName2 = "unitTestProperty5678"
    System.setProperty(propName,"bad")
    System.setProperty(propName2,"bad")
    System.setProperty("foo.bar","baz")
    def props = new Properties()
    props.setProperty("system."+propName, "good")
    props.setProperty("SYSTEM."+propName2, "good")
    props.setProperty("blarf","snoz")
    Main.convertToSystemProperties(props)
    assert System.getProperty(propName) == "good"
    assert props.contains("system."+propName) == false
    assert System.getProperty(propName2) == "good"
    assert props.contains("SYSTEM."+propName) == false
    assert System.getProperty("foo.bar") == "baz"
    assert System.getProperty("blarf") == null
  }  

  @Test
  void shouldLoadProperties() {
    def loadedFiles = []
    Main.metaClass.'static'.loadPropertiesFile = { file -> loadedFiles << file; return [foo:'bar'] }
    def props = Main.loadProperties(["mycmd", "-myarg=1", "-test"] as String[])
    assert props.myarg == "1"
    assert props.command == "mycmd"
    assert props.foo == "bar"
    assert props.test == "true"
    assert loadedFiles == ["superintendent.properties", "superintendent-secure.properties"]
  }

  @Test
  void shouldLoadPropertiesCustomFiles() {
    def loadedFiles = []
    Main.metaClass.'static'.loadPropertiesFile = { file -> loadedFiles << file; return [foo:'bar'] }
    def props = Main.loadProperties(["mycmd", "-myarg=1", "file.properties"] as String[])
    assert props.myarg == "1"
    assert props.command == "mycmd"
    assert props.foo == "bar"
    assert loadedFiles == ["file.properties"]
  }
}