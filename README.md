# lambdacd-value-stream

A library that adds upstream and downstream triggers to LambdaCD

## Status

[![Build Status](https://travis-ci.org/flosell/lambdacd-value-stream.svg)](https://travis-ci.org/flosell/lambdacd-value-stream)

[![Clojars Project](https://img.shields.io/clojars/v/lambdacd-value-stream.svg)](https://clojars.org/lambdacd-value-stream)


## When to use this

When you want to split your pipeline up into multiple, smaller pipelines
and trigger one pipeline from another.

## Usage

### Initialization
```clojure
(let [foo-pipeline (lambdacd/assemble-pipeline foo-structure foo-config)
      bar-pipeline (lambdacd/assemble-pipeline bar-structure bar-config)]
    ; ...
    (vaule-stream/initialize-value-stream {:foo foo-pipeline
                                           :bar bar-pipeline})
    ; ...
    )
```

### Wait for upstream pipeline to complete

```clojure
(defn wait-for-foo-pipeline [args ctx]
  (value-stream/wait-for-pipline-success :foo ctx))
```

See [example](example/simple_pipeline.clj) for a complete example.

## Features

* [x] Wait for upstream success
* [ ] Trigger from upstream step
* [ ] Visualization

## License

Copyright © 2016 Florian Sellmayr

Distributed under the Apache License 2.0