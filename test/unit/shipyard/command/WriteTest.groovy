package shipyard.command

import shipyard.*
import org.junit.*
import groovy.mock.interceptor.MockFor

public class WriteTest {
  @Test
  public void should_show_usage() {
    def cmd = new Write()
    assert cmd.description
    assert cmd.args.find { it.name == "path" } 
    assert cmd.args.find { it.name == "key" } 
    assert cmd.args.find { it.name == "value" } 
    assert cmd.args.find { it.name == "file" } 
  }

  @Test
  public void should_write_value() {
    def foundPath, foundKey, foundValue
    def props = new Properties()
    props.path = "foo/bar"
    props.key = "mykey"
    props.value = "hello"
    def svc = new Object() {
      void write(String path, String key, Object value) { foundPath = path; foundKey = key; foundValue = value }
    }
    def write = new Write()
    write.execute(svc as StorageService, props)
    assert foundPath == props.path
    assert foundKey == "mykey"
    assert foundValue == "hello"
  }

  @Test
  public void should_write_file() {
    def foundPath, foundKey, foundValue, checkedFile
    def props = new Properties()
    props.path = "foo/bar"
    props.key = "mykey"
    props.file = "hello.file"
    def svc = new Object() {
      void write(String path, String key, Object value) { foundPath = path; foundKey = key; foundValue = value }
    }
    def write = new Write()
    def fileMock = new MockFor(File)
    fileMock.demand.isFile { checkedFile = true; return true }
    fileMock.demand.getText { return "hello" }
    fileMock.use {
      write.execute(svc as StorageService, props)
    }
    assert checkedFile
    assert foundPath == props.path
    assert foundKey == "mykey"
    assert foundValue == "hello"
  }
}