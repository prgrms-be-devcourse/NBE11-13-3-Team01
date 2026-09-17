FROM alpine:3.22 AS build

RUN apk add --no-cache build-base
WORKDIR /src
COPY route_optimizer.c route_optimizer.h route_optimizer_server.c Makefile ./
RUN make server

FROM alpine:3.22
COPY --from=build /src/build/route_optimizer_server /usr/local/bin/route-optimizer-server
EXPOSE 8091
ENV ROUTE_OPTIMIZER_PORT=8091
ENTRYPOINT ["/usr/local/bin/route-optimizer-server"]
