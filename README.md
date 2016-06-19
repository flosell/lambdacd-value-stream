# lambdacd-value-stream

A library that adds upstream and downstream triggers to LambdaCD

## Status

[![Build Status](https://travis-ci.org/flosell/lambdacd-value-stream.svg)](https://travis-ci.org/flosell/lambdacd-value-stream)

[![Clojars Project](https://img.shields.io/clojars/v/lambdacd-value-stream.svg)](https://clojars.org/lambdacd-value-stream)


## When to use this

When you want to split your pipeline up into multiple, smaller pipelines
and trigger one pipeline from another.

## Usage

### Initialization (version >= 0.2.0)

```clojure
(let [foo-config {; ...
                  :value-stream {:pipeline-id :foo}}
      foo-config {; ...
                  :value-stream {:pipeline-id :bar}}
      foo-pipeline (lambdacd/assemble-pipeline foo-structure foo-config)
      bar-pipeline (lambdacd/assemble-pipeline bar-structure bar-config)]
    ; ...
    (value-stream/initialize-value-stream [foo-pipeline bar-pipeline]
    ; ...
    )
```

### Initialization (version < 0.2.0)

```clojure
(let [foo-pipeline (lambdacd/assemble-pipeline foo-structure foo-config)
      bar-pipeline (lambdacd/assemble-pipeline bar-structure bar-config)]
    ; ...
    (value-stream/initialize-value-stream {:foo foo-pipeline
                                           :bar bar-pipeline})
    ; ...
    )
```

### Wait for upstream pipeline to complete

```clojure
(defn wait-for-foo-pipeline [args ctx]
  (value-stream/wait-for-pipline-success :foo ctx))
```

### Wait for trigger from upstream pipeline (version >= 0.2.0)

```clojure
(defn wait-for-upstream-trigger [args ctx]
  ; you can also inline this into the pipeline-structure
  (value-stream/wait-for-upstream-trigger args ctx))
```

### Trigger downstream pipeline (version >= 0.2.0)

```clojure
(defn trigger-bar-pipeline [args ctx]
  (value-stream/trigger-downstream-pipeline :bar args ctx))
```

See [example](example/simple_pipeline.clj) for a complete example.

## Features

* [x] Wait for upstream success
* [x] Trigger from upstream step
* [ ] Visualization

## License

Copyright © 2016 Florian Sellmayr

Distributed under the Apache License 2.0