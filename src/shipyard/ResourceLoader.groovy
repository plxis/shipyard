package shipyard

public class ResourceLoader {
  public static def withResourceStream(String name, Closure closure) {
    def stream
    def homeDir = Shipyard.homeDir

    try {
      // Try grabbing from classpath
      stream = ClassLoader.getSystemResourceAsStream(name)
      if (stream == null) {
        // Not in classpath, try grabbing file on filesystem
        if (!name.startsWith(File.separator)) {
          // Relative to product install location
          name = homeDir + File.separator + name
        }
        def f = new File(name)
        if (f.isFile()) {
          stream = f.newInputStream()
        }
      }
      if (!stream) {
        throw new ShipyardException("Resource not found; name=" + name + "; homeDir=${homeDir}")
      } else {
        return closure(stream)
      }
    } catch (Exception e) {
      throw new ShipyardException("Resource is not accessible; name=" + name + "; reason=${e.message}")
    } finally {
      stream?.close()
    }
  }
}