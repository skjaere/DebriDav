# Observability

The docker compose file under example/observability contains some additional services to help
monitor and debug DebriDav:

- [Prometheus](https://prometheus.io/)
- [Grafana](https://grafana.com/)
- [Dozzle](https://dozzle.dev/)

## Grafana dashboard

To view the Grafana dashboard navigate to http://localhost:3000. The default username and password is `admin`.
Then Select the Debridav: Platform dashboard under the DebriDav folder. This dashboard is immutable

## Dozzle

Dozzle is useful for viewing container logs. It can be accessed at http://localhost:8082