# Solution Description
This section describes the elements of the solution, both frameworks used and rationale, and design and coding
decisions, with particular reference to the requirements of the [Assigment](docs/task.md).

## Play Framework
The service is built using the Play framework, as it provides a good starting point for this project: 
it's convention-over-configuration approach to URL routes, configuration and HTTP actions simplify the work needed.
The inclusion of Akka-streams in the framework is a further advantage, allowing the solution to scale as required,
with internal and external reactive asynchronous semantics available out of the box.

## PostgreSql
The storage requirements of the solution are straight-forward: a log of each job requested is kept, including 
the requesting customer, the request and the date and time of the request.  The outcome of the attempt to 
optimize will also be recorded: success or failure, and in the former case, the solution returned. 
An optimistic locking mechanism is implemented to prevent concurrent update issues.

## Akka-streams & Processing pipeline
Based on the the 'pipes & filters' enterprise pattern, the implementation of the solution can be visualized
by the following flow:

```
  +---------+    +--------+    +-------+    +-------+    +--------+
  | Request | => | Verify | => | Start | => | Solve | => | Finish |
  +---------+    +--------+    +-------+    +-------+    +--------+
```
1.  Request: the user-supplied request is transformed into the appropriate domain representation
2.  Verify: the request is checked for validity, with any errors being translated into a useful error message
3.  Start: a valid request is stored in the 'job' table, with a status of 'created'
4.  Solve: the optimization algorithm is applied and the result, along with status, updated to the database
5.  Finish: the result is returned to the caller, either as a success, or information failure status

Using a 'white-board-to-code' approach, this flow is implemented verbatim by the line below:
```
    source.async via verifyRequest via start via solve via finish runWith Sink.seq
```
The framework, providing execution context and a supervisor, abstracts the essential yet distracting
matters of error handling and concurrency from the application code.

The approach taken here is to 'flow' each request through the pipeline from start to finish, using Scala's `Try`
container as the processing frame.  If any stage fails, the failure is passed thru and returned, where the client
is free to interpret the meaning of failure, for example, as a 4xxx or 5xx response code.

Although this is only used for single requests at a time, the pipeline is capable of stream processing, unchanged.

## Domain modelling / DSL
Rather than using raw data-types, such as strings and numbers to represent the information consumed and generated,
this service uses a 'bounded context' style of domain model.  The advantages of this include:
1.   The values input and output by the service are strictly validated, reducing errors, making error messages more
useful and, importantly, protecting the service for certain types of attack (e.g., SQL injection) at it's boundary
2.  Reasoning about the application logic is done in the domain terminology, e.g., paint and finishes, rather than
arbitrary number codes.

## Security
It is assumed that this service is deployed behind an API gateway, that is responsible for authenticating users.
This gateway, on successful authentication, will have issued a time-limited JWToken, detailing the user's email
address (used as a natural key) and role.  This service therefore is only concerned with authorization, using the
assigned role for the JWT to determine what actions can be performed by a user.  All production traffic must be
over https to prevent tokens being intercepted and used malignantly.

A command-line utility is provided, `security.TokenTool`, to generate a limited-time JWT for testing purposes, 
using the application secret set in the deployed service (e.g., APP_SECRET, available from the Heroku dashboard)

## Metrics
Metrics for monitoring the service are available from the endpoint (e.g.):
```
http://localhost:9000/admin/metrics
```
And return a JSON document detailing JVM metrics and any metrics added by this service.  Currently, this is limited
to the count of times the optimizer succeeded and failed to find a solution:
```json
    ...
    "counters": {
        "optimize-failures": {
            "count": 0
        },
        "optimize-successful": {
            "count": 6
        }
    }
```
In production, these metrics could be consumed bt, for example, Prometheus, and rendered in Grafana, to derive 
rate information (e.g., tps) as well as setting alerts to notify of unusual conditions.  The `/metrics` endpoint is also
suitable for application health monitoring, e.g., by Nagios, or Vigio (in the Heroku ecosphere)

## Scalability
The service is deployed as a [Heroku Dyno](https://www.heroku.com/dynos), providing the ability to scale and fail-over
(e.g., if the hardware fails, the dyno manager will move an instance to new server and restart).  If auto-scaling is
enabled, if request latency increases beyond a pre-set threshold (for example, if there is a sudden run on paint-shop
jobs, or particularly comples optimizations are requested), the number of service instances running will be increased
(up to a pre-set limit) to handle the load, and scaled back down again as conditions improve.
For this to be possible, it is important that the service itself is stateless.

