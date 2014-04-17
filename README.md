# Flowdock to Elasticsearch

The goal of this project is to load messages from [Flowdock](https://flowdock.com/) and index them in [Elasticsearch](http://www.elasticsearch.org/), so that you can visualize your usage of Flowdock through [Kibana](http://www.elasticsearch.org/overview/kibana/) for example.

It uses the [Flowdock REST API](https://www.flowdock.com/api/rest) to load all flows to which you have access, and then load all messages from each of them.

For the moment it imports chat messages and mails.

Also note that it stores its status (the latest imported message ID by flow) in Elasticsearch (in an index named `flowdock-int`), so that the next time you run it, it will only load the new messages.

## Pre-requisites

* [Java](http://www.oracle.com/technetwork/java/javase/overview/index.html) 7+
* [SBT](http://www.scala-sbt.org/) 0.13.x to compile and run
* A [Flowdock](https://flowdock.com/) account with an [API Token](https://flowdock.com/account/tokens)
* Access to an [Elasticsearch](http://www.elasticsearch.org/) 1.1.x instance (through the "Transport TCP" port, not the HTTP port)
* _Optionally_ a [Kibana](http://www.elasticsearch.org/overview/kibana/) 3.x instance plugged on the previous Elasticsearch instance

## Configuration

Copy the `src/main/resources/application.conf.sample` file to `src/main/resources/application.conf` and add your [API Token](https://flowdock.com/account/tokens).

You can also read the `src/main/resources/reference.conf` file to see all elements that can be configured (flowdock & elasticsearch settings).

## Running

Just run

```
sbt run
```

It should print something like :

```
[run-main-0] INFO flowdock2es - Starting Flowdock To Elasticsearch import ...
[run-main-0] INFO flowdock2es - Found 3 flows and 42 users
[run-main-0] INFO flowdock2es - Current status is : Map()
[run-main-0] INFO flowdock2es - Loading ALL messages from flow 'flow-1' ...
[run-main-0] INFO flowdock2es - 42 messages imported from flow 'flow-1'
[run-main-0] INFO flowdock2es - Loading ALL messages from flow 'flow-2' ...
[run-main-0] INFO flowdock2es - 256 messages imported from flow 'flow-2'
[run-main-0] INFO flowdock2es - Loading ALL messages from flow 'flow-3' ...
[run-main-0] INFO flowdock2es - 512 messages imported from flow 'flow-3'
[run-main-0] INFO flowdock2es - Flowdock To Elasticsearch import finished !
```

## Visualization with Kibana

We provide a [Kibana](http://www.elasticsearch.org/overview/kibana/) dashboard in `src/main/resources/kibana/flowdock.json` to help you visualize your usage of Flowdock.

You can just import it in your Kibana installation, and you should be able to interact with your data in no time.

![](https://raw.githubusercontent.com/vbehar/flowdock2es/master/src/main/resources/kibana/screenshot.png)

## License

Copyright 2014 the original author or authors.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.