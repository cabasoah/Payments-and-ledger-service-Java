output "ecr_repository_url" {
  description = "Push images here."
  value       = aws_ecr_repository.app.repository_url
}

output "ecs_cluster" {
  value = aws_ecs_cluster.app.name
}

output "ecs_service" {
  value = aws_ecs_service.app.name
}

output "db_endpoint" {
  description = "Private RDS endpoint (reachable only from the app task)."
  value       = aws_db_instance.db.address
}

output "region" {
  value = var.region
}
