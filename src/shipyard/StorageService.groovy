package shipyard

public interface StorageService {
  public void init(Properties props)
  public void delete(String path)
  public List list(String path)
  public Map readAll(String path)
  public String readKey(String path, String key)
  public void write(String path, String key, Object value)
  public void write(String path, Object values)
  public void restore(String filename)
}