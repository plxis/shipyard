package shipyard

import shipyard.command.*
import shipyard.vault.*
import java.util.concurrent.Callable
import groovy.mock.interceptor.MockFor
import org.junit.*

public class ResourceLoaderTest {
  @Test
  void testWithResourceStream() {
    def result = ResourceLoader.withResourceStream("log4j.xml") { stream ->
      assert stream?.text?.contains("log4j")
      return "abc"
    } 
    assert result == "abc"
  }
}