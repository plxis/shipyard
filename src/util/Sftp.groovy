package util

import java.nio.charset.Charset
import java.util.Properties
import java.nio.file.*
import groovy.mock.interceptor.*
import com.jcraft.jsch.*
import util.*

/*
 * http://www.jcraft.com/jsch/examples/Sftp.java.html
 * https://github.com/is/jsch
 * https://github.com/jpbriend/sftp-example/blob/master/src/main/java/com/infinit/sftp/SftpClient.java
 *
 * Other sftp commands not implemented yet.
 * - channel.cd(path);
 * - channel.lcd(".");
 * - channel.rm(path);
 * - channel.mkdir(path);
 * - channel.rmdir(path);
 * - channel.chgrp(foo, path);
 * - channel.chown(foo, path);
 * - channel.chmod(foo, path);
 * - channel.pwd();
 * - channel.lpwd();
 * - channel.put(p1, p2, monitor, mode);
 * - channel.hardlink(p1, p2); }
 * - channel.rename(p1, p2);
 * - channel.symlink(p1, p2);
 * - stat = channel.statVFS(p1);
 * - attrs = channel.stat(p1);
 * - attrs = channel.lstat(p1);
 * - filename = channel.readlink(p1);
 * - filename = channel.realpath(p1);
 * - channel.version());
 */
class Sftp {
  static log = Log.getLogger(this)

  // Produce a representative mock of the sftp client for testing.
  static mock(alternateFiles = null) {
    def baseFiles = [
      FileData.sample(filename: "file2-11111213141516.csv", modifyTime: DateTime.parse("1111-12-13 14:15:16")),
      FileData.sample(filename: "file2-11111213141518.csv", modifyTime: DateTime.parse("1111-12-13 14:15:18")),
    ]

    def sftpMock = new MockFor(Sftp)
    sftpMock.ignore.factory() { username, host, port ->
      new Sftp(username, host, port) {
        def ls(path = ".") {
          alternateFiles != null ? alternateFiles : baseFiles
        }
        def get(remotePath, destinationPath) {
          new File(destinationPath) {
            String getName() { destinationPath }
            int size() { 123 }
            boolean exists() { true }
          }
        }
        def put(localile, remotePath) {
        }
        def close() {}
      }
    }
    sftpMock
  }

  static factory(username, hostname, port) {
    new Sftp(username, hostname, port)
  }

  def username
  def hostname
  def password = ""
  def port

  def userHome
  def session
  def channel

  protected Sftp(username, hostname, port) {
    this.username = username
    this.hostname = hostname
    this.port = port

    this.userHome = System.getProperty('user.home')
  }

  def ls(path = ".") {
    command(path: path) { channel ->
      channel.ls(path)
    }.collect { FileData.fromLsEntry(it) }
  }

  def get(remotePath, destinationPath) {
    command(remotePath: remotePath, destinationPath: destinationPath) { channel ->
      channel.get(remotePath, destinationPath)
    }
  }

  def put(localFile, remotePath) {
    command(localFile: localFile, remotePath: remotePath) { channel ->
      channel.put(localFile, remotePath, ChannelSftp.OVERWRITE)
    }
  }

  def close() {
    session.disconnect()
  }

  private command(args, closure) {
    channel(username, hostname, port, args, closure)
  }

  private channel(username, hostname, port, args, closure) {
    channel = channel ?: constructChannel(username, hostname, port)

    try {
      closure(channel)
    }
    catch (com.jcraft.jsch.SftpException e) {
      log.warn("${e.message}: ${args}")
      throw e
    }
  }

  private constructChannel(username, hostname, port) {
    def jsch = new JSch()
    session = jsch.getSession(username, hostname, port)
    session.setPassword(password.getBytes(Charset.forName("ISO-8859-1")))
    session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")

    if (Files.exists(Paths.get("${userHome}/.ssh/known_hosts"))) {
      jsch.setKnownHosts("${userHome}/.ssh/known_hosts")
    }

    if (Files.exists(Paths.get("${userHome}/.ssh/id_rsa"))) {
      jsch.addIdentity("${userHome}/.ssh/id_rsa")
    }
    if (Files.exists(Paths.get("${userHome}/.ssh/id_dsa"))) {
      jsch.addIdentity("${userHome}/.ssh/id_dsa")
    }

    def config = new java.util.Properties()
    config.put("StrictHostKeyChecking", "no")
    session.setConfig(config)

    try {
      session.connect()
    }
    catch (com.jcraft.jsch.JSchException e) {
      log.error("Connection failed to: '${hostname}:${port}' as '${username}': ${e}")
      throw e
    }

    def channel = session.openChannel("sftp")
    channel.connect()

    channel
  }
}


