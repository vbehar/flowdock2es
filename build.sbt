//  Copyright 2014 the original author or authors.
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.

name := "flowdock2es"

version := "1.0"

scalaVersion := "2.10.3"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

javacOptions ++= Seq("-source", "1.7", "-target", "1.7")

resolvers ++= Seq(
    "Sonatype snapshots"                at "http://oss.sonatype.org/content/repositories/snapshots"
)

libraryDependencies ++= Seq(
    "com.sksamuel.elastic4s"            %% "elastic4s"          % "1.1.0.0",
    "net.databinder.dispatch"           %% "dispatch-core"      % "0.11.0",
    "net.liftweb"                       %% "lift-json"          % "3.0-SNAPSHOT",
    "joda-time"                         %  "joda-time"          % "2.3",
    "com.typesafe"                      %  "config"             % "1.2.0",
    "org.slf4j"                         %  "slf4j-api"          % "1.7.7",
    "org.slf4j"                         %  "slf4j-simple"       % "1.7.7"
)