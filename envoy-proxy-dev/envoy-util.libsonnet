{
    listener(cluster_name, grpc_web_port)::
    {
       "name": cluster_name + "_listener",
       "address": {
         "socket_address": {
           "address": "0.0.0.0",
           "port_value": grpc_web_port
         }
       },
       "access_log": [
         {
           "name": cluster_name + "_listener_log",
           "typed_config": {
             "@type": "type.googleapis.com/envoy.extensions.access_loggers.file.v3.FileAccessLog",
             "path": "../log/envoy-listener-" + cluster_name + ".log"
           }
         }
       ],
       "filter_chains": [
         {
           "filters": [
             {
               "name": "envoy.filters.network.http_connection_manager",
               "typed_config": {
                 "@type": "type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager",
                 "codec_type": "auto",
                 "stat_prefix": "ingress_http",
                 "route_config": {
                   "name": "local_route",
                   "virtual_hosts": [
                     {
                       "name": "local_service",
                       "domains": [
                         "*"
                       ],
                       "routes": [
                         {
                           "match": {
                             "prefix": "/"
                           },
                           "route": {
                             "cluster": cluster_name,
                             "timeout": "0s",
                             "max_stream_duration": {
                               "grpc_timeout_header_max": "0s"
                             }
                           }
                         }
                       ],
                       "cors": {
                         "allow_origin_string_match": [
                           {
                             "prefix": "*"
                           }
                         ],
                         "allow_methods": "GET, PUT, DELETE, POST, OPTIONS",
                         "allow_headers": "authorization,keep-alive,user-agent,cache-control,content-type,content-transfer-encoding,custom-header-1,x-accept-content-transfer-encoding,x-accept-response-streaming,x-user-agent,x-grpc-web,grpc-timeout",
                         "max_age": "1728000",
                         "expose_headers": "custom-header-1,grpc-status,grpc-message"
                       }
                     }
                   ]
                 },
                 "http_filters": [
                   {
                     "name": "envoy.filters.http.grpc_web",
                     "typed_config": {
                       "@type": "type.googleapis.com/envoy.extensions.filters.http.grpc_web.v3.GrpcWeb"
                     }
                   },
                   {
                     "name": "envoy.filters.http.cors",
                     "typed_config": {
                       "@type": "type.googleapis.com/envoy.extensions.filters.http.cors.v3.Cors"
                     }
                   },
                   {
                     "name": "envoy.filters.http.router",
                     "typed_config": {
                       "@type": "type.googleapis.com/envoy.extensions.filters.http.router.v3.Router"
                     }
                   }
                 ]
               }
             }
           ]
         }
       ]
    },

    cluster(cluster_name, grpc_address, grpc_port)::
    {
      "name": cluster_name,
      "connect_timeout": "0.25s",
      "type": "logical_dns",
      "http2_protocol_options": {},
      "lb_policy": "round_robin",
      "load_assignment": {
        "cluster_name": "cluster_0",
        "endpoints": [
          {
            "lb_endpoints": [
              {
                "endpoint": {
                  "address": {
                    "socket_address": {
                      "address": grpc_address,
                      "port_value": grpc_port
                    }
                  }
                }
              }
            ]
          }
        ]
      }
    }
}
