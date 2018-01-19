# -*- mode: ruby -*-
# vi: set ft=ruby :

def get_config()
  JSON.parse(File.read("conf/shipyard-config.json"))
end

Vagrant.configure(2) do |config|
  
  config.vm.provider "virtualbox" do |v|
    v.memory = 2048
  end

  config.vm.define "vault-server" do |box| 
    box.vm.box = "centos/7"

    box.vm.network "forwarded_port", 
      host_ip: "127.0.0.1", 
      guest: 8200, 
      host: 8201, 
      auto_correct: true

    box.vm.provision "ansible" do |ansible|
      ansible.playbook = "ansible/site.yml"
      ansible.extra_vars = get_config()
    end
  end
end
