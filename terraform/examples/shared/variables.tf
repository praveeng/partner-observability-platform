variable "environment" {
  type = string
  validation {
    condition     = contains(["DEV", "STAGE", "PROD"], var.environment)
    error_message = "Expected DEV, STAGE, or PROD."
  }
}
variable "market" {
  type    = string
  default = "synthetic-uk"
}
