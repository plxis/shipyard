package shipyard.command

import shipyard.*
import org.junit.*
import groovy.mock.interceptor.MockFor

public class KeysTest {
  @Test
  public void should_include_all_args() {
    def cmd = new Keys()
    assert cmd.description
    assert cmd.args.find { it.name == "path" }
  }

  @Test
  public void should_list() {
    def expected = ["mykey", "akey2"]
    def foundPath
    def props = new Properties()
    props.path = "foo/bar"
    def svc = new Object() {
      Object list(String path) { foundPath = path; return expected }
    }
    def cmd = new Keys()
    def actual = cmd.execute(svc as StorageService, props)
    assert actual == 0
    assert cmd.output == "[mykey, akey2]"
    assert foundPath == props.path
  }
}