persistor = 'com.bloidonia.jdbcpersistor'
metrics   = 'com.bloidonia.metrics'

def createDbStructure( eb, Closure done ) {
    eb.send( persistor, [ action:'execute',
                          stmt:  '''CREATE TABLE albums 
                                   |  ( id INTEGER GENERATED BY DEFAULT AS IDENTITY (START WITH 1 INCREMENT BY 1) NOT NULL,
                                   |    artist VARCHAR(80),
                                   |    title VARCHAR(80),
                                   |    genre VARCHAR(80),
                                   |    price REAL )'''.stripMargin() ] ) {
        eb.send( persistor, [ action:'execute',
                              stmt:  '''CREATE TABLE users 
                                       |  ( firstname  VARCHAR(80),
                                       |    lastname VARCHAR(80),
                                       |    email VARCHAR(80),
                                       |    username VARCHAR(80),
                                       |    password VARCHAR(80) )'''.stripMargin() ] ) {
            eb.send( persistor, [ action:'execute',
                                  stmt:  'CREATE SEQUENCE ORDERNO' ] ) {
                eb.send( persistor, [ action:'execute',
                                      stmt:  '''CREATE TABLE orders
                                               |  ( username VARCHAR(80),
                                               |    ordernum INTEGER,
                                               |    albumid INTEGER,
                                               |    quantity INTEGER )'''.stripMargin() ] ) {
                    done()
                }
            }
        }
    }
}

nextOrder = {->}
nextOrder = { eb, ordernum, username, albums, Closure callback ->
    if( albums.size() < 1 ) {
        { reply ->
            reply.reply( [ action: 'commit' ] ) { r ->
                callback( r )
            }
        }
    }
    else {
        { reply ->
            Closure next = nextOrder( eb, ordernum, username, albums.drop( 1 ), callback )
            def values = [ username, ordernum, albums[ 0 ].album._id, albums[ 0 ].quantity ]
            reply.reply( [ action:'update',
                           stmt:  '''INSERT INTO orders ( username, ordernum, albumid, quantity )
                                    |  VALUES ( ?, ?, ?, ? )'''.stripMargin(),
                           values: values ], next )
        }
    }
}

def saveOrder( eb, username, albums, Closure done ) {
    eb.send( persistor, [ action:'transaction' ] ) { r ->
        r.reply( [ action:'select', stmt:'CALL NEXT VALUE FOR ORDERNO' ] ) { reply ->
            def ordernum = reply.body().result[ 0 ].'@p0'
            nextOrder( eb, ordernum, username, albums, done )( reply )
        }
    }
}

vertx.eventBus.with { eb ->
    registerHandler( 'bloidonia.businessrules' ) { message ->
        def body = message.body()
        switch( body.action ) {
            case 'createdb':
                createDbStructure( eb ) {
                    message.reply( [ status: 'ok' ] )
                }
                break
            case 'find':
                String stmt = "SELECT * FROM $body.collection"
                send( persistor, 
                      [ action:'select', stmt:stmt ] ) { reply ->
                    message.reply( reply.body() )
                }
                break
            case 'findone':
                if( body.collection == 'users' && body.matcher ) { // login attempt
                    eb.send( metrics, [ name:'login.timer', action:'start' ] )
                    eb.send( metrics, [ name:'login.attempts', action:'inc' ] )
                    send( 'com.bloidonia.jdbcpersistor',
                          [ action:'select',
                            stmt:  'SELECT username FROM users WHERE username=? AND password=?',
                            values:[ body.matcher.username, body.matcher.password ] ] ) { response ->
                        boolean success = response.body().result.USERNAME?.size()
                        if( success ) {
                          eb.send( metrics, [ name:'login.successes', action:'inc' ] )
                        }
                        else {
                          eb.send( metrics, [ name:'login.failures', action:'inc' ] )
                        }
                        message.reply( [ status: success ? 'ok' : 'error', result:body.matcher ] )
                        eb.send( metrics, [ name:'login.timer', action:'stop' ] )
                    }
                }
                break
            case 'save':
                if( body.collection == 'orders' ) {
                    eb.send( metrics, [ name:'orders.saved', action:'inc' ] )
                    saveOrder( eb, body.document.username, body.document.items ) {
                        message.reply( [ status: 'ok' ] )
                    }
                }
                else {
                    String stmt = """INSERT INTO ${body.collection}
                                    |  ( ${body.document.keySet().join( ',' )} )
                                    |  VALUES( ${(['?'] * body.document.keySet().size()).join( ',' )} )""".stripMargin()
                    send( persistor, 
                          [ action:'update', stmt:stmt, values:body.document.values().toList() ] ) { reply ->
                        message.reply( reply.body() )
                    }
                }
        }
    }
}

