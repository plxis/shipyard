package shipyard.command

import shipyard.*
import org.junit.*
import groovy.mock.interceptor.MockFor

public class DeleteTest {
  @Test
  public void should_include_all_args() {
    def cmd = new Delete()
    assert cmd.description
    assert cmd.args.find { it.name == "path" }
  }

  @Test
  public void should_list() {
    def foundPath
    def props = new Properties()
    props.path = "foo/bar"
    def svc = new Object() {
      Object delete(String path) { foundPath = path }
    }
    def cmd = new Delete()
    def actual = cmd.execute(svc as StorageService, props)
    assert foundPath == props.path
  }
}