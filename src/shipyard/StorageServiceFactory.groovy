package shipyard

public class StorageServiceFactory {
  public static StorageService create(Properties props) {
    def storageSvc = Class.forName(props.storageServiceClass).newInstance()
    storageSvc.init(props)
    return storageSvc
  }
}