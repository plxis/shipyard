variable "context"                   { default = "shipyard" }
variable "foundry_state_bucket"      { }
variable "foundry_state_region"      { default = "us-east-1" }
variable "foundry_state_key"         { default = "/terraform-states/site-foundry/terraform.tfstate" }
variable "aws_region"                { default = "us-east-1" }
variable "aws_ec2_instance_type"     { default = "t2.micro" }
variable "host_instance_count_min"               { default = 1 }
variable "host_instance_count_max"               { default = 2 }
variable "host_instance_count_desired"           { default = 1 }
variable "aws_cloudwatch_log_group"  { }
variable "docker_image"              { }

# Although this allows for DRY configurations, Terraform still doesn't handle 
# locals well enough to rely on.
# locals {
  # private_subnets   = "${data.terraform_remote_state.foundry.private_subnets}"
  # public_subnets    = "${data.terraform_remote_state.foundry.public_subnets}"
# }

data "terraform_remote_state" "foundry" {
  backend = "s3"

  config {
    region = "${var.foundry_state_region}"
    bucket = "${var.foundry_state_bucket}"
    key    = "${var.foundry_state_key}"
  }
}

provider "aws" {
  region = "${var.aws_region}"
}

data "aws_caller_identity" "current" {}

data "template_file" "user-data" {
  template = "${file("${path.module}/user-data.tpl")}"

  vars {
    context              = "${var.context}"
    shipyard_efs_target  = "${aws_efs_mount_target.shipyard-data-target.0.dns_name}"
    shipyard_local_mount = "/shipyard-data"
    users_efs_target     = "${data.terraform_remote_state.foundry.user_data_efs_dns_name}"
    users_local_mount    = "/users"
    log_group            = "${var.aws_cloudwatch_log_group}"
    aws_account_id       = "${data.aws_caller_identity.current.account_id}"
    docker_image         = "${var.docker_image}"
  }
}

resource "tls_private_key" "rsa-key" {
  algorithm   = "RSA"
}

resource "aws_key_pair" "ec2-key" {
  key_name   = "${var.context}"
  public_key = "${tls_private_key.rsa-key.public_key_openssh}"
}

resource "aws_security_group" "base-shipyard" {
  name        = "${var.context}-base-shipyard"
  description = "Base ingress/egress behavior for Shipyard hosts"
  vpc_id      = "${data.terraform_remote_state.foundry.vpc_id}"

  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    security_groups = [ "${aws_elb.internal-elb.source_security_group_id}", "${data.terraform_remote_state.foundry.jump_host_sg}" ]
  }

  # TODO: Restrict egress traffic to only the hosts that Ansible will need to connect to?
  egress {
    from_port       = 0
    to_port         = 0
    protocol        = "-1"
    cidr_blocks     = ["0.0.0.0/0"]
  }

  tags {
    Name    = "${var.context}-base-shipyard"
    Context = "${var.context}"
  }
}

data "aws_ami" "amazon-linux" {
  owners = ["amazon"]
  most_recent = true

  filter {
    name   = "name"
    values = ["amzn-ami-*-x86_64-gp2"]
  }
}

resource "aws_launch_configuration" "host" {
  name_prefix     = "lc-${var.context}-"
  image_id        = "${data.aws_ami.amazon-linux.id}"
  instance_type   = "${var.aws_ec2_instance_type}"
  security_groups = [ "${aws_security_group.base-shipyard.id}" ]
  user_data       = "${data.template_file.user-data.rendered}"
  key_name        = "${aws_key_pair.ec2-key.key_name}"

  # Minimize downtime by creating a new launch config before destroying old one
  lifecycle {
    create_before_destroy = true
  }

  iam_instance_profile = "${aws_iam_instance_profile.profile.id}"
}

resource "aws_security_group" "elb" {
  name        = "elb-${var.context}"
  description = "SSH load-balancer security group"
  vpc_id      = "${data.terraform_remote_state.foundry.vpc_id}"

  ingress {
    from_port       = 22
    to_port         = 22
    protocol        = "tcp"
    security_groups = [ "${data.terraform_remote_state.foundry.jump_host_sg}" ]
  }
  
  egress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags {
    Name    = "elb-${var.context}"
    Context = "${var.context}"
  }
}

resource "aws_elb" "internal-elb" {
  name            = "elb-${var.context}"
  subnets         = ["${data.terraform_remote_state.foundry.private_subnets}"]
  security_groups = ["${aws_security_group.elb.id}"]
  internal        = true
  
  listener {
    instance_port     = 22
    instance_protocol = "tcp"
    lb_port           = 22
    lb_protocol       = "tcp"
  }

  health_check {
    healthy_threshold   = 2
    unhealthy_threshold = 2
    timeout             = 10
    target              = "TCP:22"
    interval            = 15
  }

  tags {
    Name    = "elb-${var.context}"
    Context = "${var.context}"
  }
}

resource "aws_autoscaling_group" "default" {
  name                 = "asg-${var.context}-${aws_launch_configuration.host.id}"
  max_size             = "${var.host_instance_count_max}"
  min_size             = "${var.host_instance_count_min}"
  desired_capacity     = "${var.host_instance_count_desired}"
  launch_configuration = "${aws_launch_configuration.host.name}"
  min_elb_capacity     = 1
  vpc_zone_identifier  = [ "${data.terraform_remote_state.foundry.private_subnets}" ]
  load_balancers       = [ "${aws_elb.internal-elb.id}" ]
  enabled_metrics      = ["GroupMinSize","GroupMaxSize","GroupDesiredCapacity","GroupInServiceInstances","GroupPendingInstances","GroupStandbyInstances","GroupTerminatingInstances","GroupTotalInstances"]
  
  lifecycle {
    create_before_destroy = true
  }

  tag {
    key                 = "Name"
    value               = "${var.context}-host"
    propagate_at_launch = true
  }

  tag {
    key                 = "Context"
    value               = "${var.context}"
    propagate_at_launch = true
  }
}

resource "aws_route53_record" "dns-loadbalancer" {
  zone_id = "${data.terraform_remote_state.foundry.public_zone_id}"
  name    = "shipyard"
  type    = "A"

  alias {
    name                   = "${aws_elb.internal-elb.dns_name}"
    zone_id                = "${aws_elb.internal-elb.zone_id}"
    evaluate_target_health = true
  }
}

resource "aws_route53_record" "dns-internal" {
  zone_id   = "${data.terraform_remote_state.foundry.private_zone_id}"
  name      = "shipyard"
  type      = "A"

  alias {
    name                   = "${aws_elb.internal-elb.dns_name}"
    zone_id                = "${aws_elb.internal-elb.zone_id}"
    evaluate_target_health = true
  }
}


data "aws_iam_policy_document" "assume-role-policy-document" {
  statement {
    actions = [ "sts:AssumeRole" ]
    effect  = "Allow"

    principals {
      type        = "Service"
      identifiers = [ "ec2.amazonaws.com" ]
    }
  }
}

# TODO: Make this more restrictive
# At a minimum, it will need permissions to create/destroy cloudwatch log streams
data "aws_iam_policy_document" "role-policy-document" {
  statement {
    effect    = "Allow"
    actions   = [ "*" ]
    resources = [ "*" ]
  }
}

resource "aws_iam_role" "role" {
  name               = "role-${var.context}"
  path               = "/"
  assume_role_policy = "${data.aws_iam_policy_document.assume-role-policy-document.json}"
}

resource "aws_iam_instance_profile" "profile" {
  name  = "instance-profile-${var.context}"
  role = "${aws_iam_role.role.name}"
}

resource "aws_iam_role_policy" "role-policy" {
  name   = "policy-${var.context}"
  role   = "${aws_iam_role.role.id}"
  policy = "${data.aws_iam_policy_document.role-policy-document.json}"
}

resource "aws_efs_file_system" "shipyard-data" {
  creation_token = "shipyard-efs-${var.context}"
  encrypted      = true

  tags {
    Name    = "shipyard-efs-${var.context}"
    Context = "${var.context}"
  }
}

resource "aws_security_group" "efs" {
  name        = "${var.context}-allow-ec2-mount"
  description = "Allow EC2 instance to mount EFS target"
  vpc_id      = "${data.terraform_remote_state.foundry.vpc_id}"

  ingress {
    from_port       = 2049
    to_port         = 2049
    protocol        = "tcp"
    security_groups = ["${aws_security_group.base-shipyard.id}"]
  }

  tags {
    Name    = "${var.context}-allow-ec2-mount"
    Context = "${var.context}"
  }
}

resource "aws_efs_mount_target" "shipyard-data-target" {
  count           = "${length(data.terraform_remote_state.foundry.private_subnets)}"
  file_system_id  = "${aws_efs_file_system.shipyard-data.id}"
  subnet_id       = "${element(data.terraform_remote_state.foundry.private_subnets, count.index)}"
  security_groups = ["${aws_security_group.efs.id}"]
}

data "aws_efs_mount_target" "shipyard-mount-target-info" {
  count           = "${length(data.terraform_remote_state.foundry.private_subnets)}"
  mount_target_id = "${element(aws_efs_mount_target.shipyard-data-target.*.id, count.index)}"
}

output "ssh_private_key" {
  value     = "${tls_private_key.rsa-key.private_key_pem}"
  sensitive = true
}

output "fqdn" {
  value = "${aws_route53_record.dns-loadbalancer.fqdn}"
}

output "load_balancer_dns" {
  value = "${aws_elb.internal-elb.dns_name}"
}
