## why

rabbitmq was complex and inextensible. a queue is a single, atomic thing. there are lots of good uses for that atomicity if the queue can be extended easily.

## what

a [durable queue](http://github.com/factual/durable-queue) exposing all lifecycle methods as http endpoints. new [routes](https://github.com/nathants/spq/blob/142c8cafaa35a088c4e9e1958131f7c8533358f6/test/spq/server_test.clj#L413) can be added, as well as [middleware](https://github.com/nathants/spq/blob/142c8cafaa35a088c4e9e1958131f7c8533358f6/test/spq/server_test.clj#L423) to manipulate calls to existing endpoints.

## non goals

- ultra high performance, http is a bad fit for that.

- high availability, no clustering or fail over.

- high durability, it's just local disk.

## install

you are going to need [leiningen](https://leiningen.org/#install).

```
git clone https://github.com/nathants/spq
cd spq
lein trampoline run -m spq.server 8080 resources/config.edn
```

alternately you could build a `lein uberjar` and run that with `java -jar target/spq.jar 8080 resources/config.edn`

## usage

- curl shortcuts
   ```
   get() { curl -sv localhost:8080${1}; }
   post() { curl -sv -XPOST -H "Content-Type: application/json"' localhost:8080${1}; }
   ```

- check
   ```
   >> get /stats
   {}
   ```

- put

   ```
   >> post /put?queue=test -d '{"json": true}'
   ```

- stats
   ```
   >> get /stats
   {"test": {"queued": 1, "active": 0}}
   ```

- take, stats, retry, stats

   ```
   >> post /take?queue=test
   Id: 127509770
   {"json": true}

   >> get /stats
   {"test": {"queued": 1, "active": 1}}

   >> post /retry -d 127509770

   >> get /stats
   {"test": {"queued": 1, "active": 0}}
   ```

- take, stats, complete, stats

   ```
   >> post /take?queue=test
   Id: 224513658
   {"json": true}

   >> get /stats
   {"test": {"queued": 1, "active": 1}}

   >> post /complete -d 224513658

   >> get /stats
   {"test": {"queued": 0, "active": 0}}
   ```
