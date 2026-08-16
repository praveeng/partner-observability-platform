# Local Docker Compose environment

Docker Compose definitions for synthetic local integration will be introduced as Alloy/Loki/Prometheus/Grafana configurations become real. M0 does not declare placeholder services because an empty or fake stack would provide misleading verification.

The local environment must not use production credentials or data and must exercise backend failure without coupling business availability to observability.
