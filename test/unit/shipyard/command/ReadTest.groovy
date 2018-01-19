package shipyard.command

import shipyard.*
import org.junit.*
import groovy.mock.interceptor.MockFor

public class ReadTest {
  @Test
  public void should_include_all_args() {
    def cmd = new Read()
    assert cmd.description
    assert cmd.args.find { it.name == "path" }
    assert cmd.args.find { it.name == "key" }
    assert cmd.args.find { it.name == "file" }
  }

  @Test
  public void should_read_all() {
    def expected = [mykey: "mumbo jumbo"]
    def foundPath
    def props = new Properties()
    props.path = "foo/bar"
    def svc = new Object() {
      Object readAll(String path) { foundPath = path; return expected }
    }
    def read = new Read()
    def actual = read.execute(svc as StorageService, props)
    assert actual == 0
    assert read.output == "{mykey=mumbo jumbo}"
    assert foundPath == props.path
  }

  @Test
  public void should_read_key() {
    def expected = "mumbo jumbo"
    def foundPath, foundKey
    def props = new Properties()
    props.path = "foo/bar"
    props.key = "mykey"
    def svc = new Object() {
      Object readKey(String path, String key) { foundPath = path; foundKey = key; return expected }
    }
    def read = new Read()
    def actual = read.execute(svc as StorageService, props)
    assert actual == 0
    assert read.output == expected
    assert foundPath == props.path
    assert foundKey == props.key
  }

  @Test
  public void should_read_missing_key() {
    def expected = "(empty)"
    def foundPath, foundKey
    def props = new Properties()
    props.path = "foo/bar"
    props.key = "mykey"
    def svc = new Object() {
      Object readKey(String path, String key) { foundPath = path; foundKey = key; return expected }
    }
    def read = new Read()
    def actual = read.execute(svc as StorageService, props)
    assert actual == 0
    assert read.output == expected
    assert foundPath == props.path
    assert foundKey == props.key
  }

  @Test
  public void should_read_key_and_write_to_file() {
    def expected = "mumbo jumbo"
    def foundPath, foundKey
    def props = new Properties()
    props.path = "foo/bar"
    props.key = "mykey"
    def tmpFile = File.createTempFile("ReadTest","tmp")
    props.file = tmpFile.absolutePath
    def svc = new Object() {
      Object readKey(String path, String key) { foundPath = path; foundKey = key; return expected }
    }
    def read = new Read()
    def actual = read.execute(svc as StorageService, props)
    assert actual == 0
    assert !read.output
    assert foundPath == props.path
    assert foundKey == props.key
    assert tmpFile.text == expected
  }
}