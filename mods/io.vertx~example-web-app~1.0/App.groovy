/*
 * Copyright 2011-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package webapp

// Our application config - you can maintain it here or alternatively you could
// stick it in a conf.json text file and specify that on the command line when
// starting this verticle

// Configuration for the web server
def webServerConf = [

    // Normal web server stuff

    port: 8080,
    host: 'localhost',
    ssl: true,

    // Configuration for the event bus client side bridge
    // This bridges messages from the client side to the server side event bus
    bridge: true,

    // This defines which messages from the client we will let through
    // to the server side
    inbound_permitted: [
        // Allow calls to login
        [
            address: 'vertx.basicauthmanager.login'
        ],
        // Allow calls to get static album data from the persistor
        [
            address : 'bloidonia.businessrules',
            match : [
                action : 'find',
                collection : 'albums'
            ]
        ],
        // And to place orders
        [
            address : 'bloidonia.businessrules',
            requires_auth : true,  // User must be logged in to send let these through
            match : [
                action : 'save',
                collection : 'orders'
            ]
        ]
    ],

    // This defines which messages from the server we will let through to the client
    outbound_permitted: [
        [:]
    ]
]

// Now we deploy the modules that we need

container.with {
    deployModule('com.bloidonia~mod-jdbc-persistor~2.1') { asyncResult ->
        if (asyncResult.succeeded) {
            // And when it's deployed run a script to load it with some reference
            // data for the demo
            deployModule('com.bloidonia~businessrules~1.0') { r ->
                if( r.succeeded ) {
                    println 'Static Data'
                    deployVerticle( 'StaticData.groovy' )
                    // Deploy an auth manager to handle the authentication
                    deployModule('io.vertx~mod-auth-mgr~2.0.0-final', [ persistor_address: 'bloidonia.businessrules' ])
                }
                else {
                    println "Failed to deploy ${r.cause()}"
                }
            }

            // Start the web server, with the config we defined above
            deployModule('io.vertx~mod-web-server~2.0.0-final', webServerConf)

            // Start the stats
            deployModule('com.bloidonia~mod-metrics~0.0.1-SNAPSHOT', [ address:'com.bloidonia.metrics' ] )

        } else {
            println "Failed to deploy ${asyncResult.cause()}"
        }
    }
}