package shipyard

public class Shipyard {

  static Properties props = new Properties()

  static {
    def propsFilename = "/" + Shipyard.class.simpleName.toLowerCase() + "-version.properties"
    def versionStream = Shipyard.class.getResourceAsStream(propsFilename)
    
    if (versionStream) {
      props.load(versionStream)
    }
    else {
      println "Hey, go and run 'ant init' to create the ${propsFilename} file"
      System.exit(1)
    }
  }

  public static String getVersion() {
    return props.version
  }

  public static String getBuildDate() {
    return props.buildDate
  }

  public static String getBuildTime() {
    return props.buildTime
  }

  public static String getHomeDir() {
    System.env["SHIPYARD_HOME"]
  }

  /**
   * Returns a formatted string containing the product and current lib version.
   */
  public static String genProductVersionString(String title) {
    return title + " - Version " + version + " (" + buildDate + " " + buildTime + ")"
  }
}
