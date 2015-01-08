package org.openhim.mediator.orchestration;

import akka.actor.ActorRef;
import akka.actor.ActorSelection;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import org.apache.http.HttpStatus;
import org.openhim.mediator.datatypes.AssigningAuthority;
import org.openhim.mediator.datatypes.Identifier;
import org.openhim.mediator.denormalization.PIXRequestActor;
import org.openhim.mediator.engine.MediatorConfig;
import org.openhim.mediator.engine.messages.FinishRequest;
import org.openhim.mediator.engine.messages.MediatorHTTPRequest;
import org.openhim.mediator.engine.messages.MediatorHTTPResponse;
import org.openhim.mediator.engine.messages.SimpleMediatorRequest;
import org.openhim.mediator.messages.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class RegistryActor extends UntypedActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    private MediatorConfig config;

    private ActorRef requestHandler;
    private ActorRef respondTo;
    private String message;


    public RegistryActor(MediatorConfig config) {
        this.config = config;
    }


    private void parseMessage(MediatorHTTPRequest request) {
        //get request body
        message = request.getBody();

        //parse message...
        ActorSelection parseActor = getContext().actorSelection("/user/" + config.getName() + "/parse-registry-stored-query");
        parseActor.tell(new SimpleMediatorRequest<String>(request.getRequestHandler(), getSelf(), message), getSelf());
    }

    private void lookupEnterpriseIdentifier(Identifier patientID) {
        ActorRef resolvePatientIDActor = getContext().actorOf(Props.create(PIXRequestActor.class, config));
        String enterpriseIdentifierAuthority = config.getProperties().getProperty("pix.requestedAssigningAuthority");
        String enterpriseIdentifierAuthorityId = config.getProperties().getProperty("pix.requestedAssigningAuthorityId");
        AssigningAuthority authority = new AssigningAuthority(enterpriseIdentifierAuthority, enterpriseIdentifierAuthorityId);
        ResolvePatientIdentifier msg = new ResolvePatientIdentifier(requestHandler, getSelf(), patientID, authority);
        resolvePatientIDActor.tell(msg, getSelf());
    }

    private void enrichEnterpriseIdentifier(ResolvePatientIdentifierResponse msg) {
        if (msg.getIdentifier()!=null) {
            log.info("Resolved patient enterprise identifier. Enriching message...");
            ActorSelection enrichActor = getContext().actorSelection("/user/" + config.getName() + "/enrich-registry-stored-query");
            EnrichRegistryStoredQuery enrichMsg = new EnrichRegistryStoredQuery(requestHandler, getSelf(), message, msg.getIdentifier());
            enrichActor.tell(enrichMsg, getSelf());
        } else {
            log.info("Could not resolve patient identifier");
            FinishRequest response = new FinishRequest("Unknown patient identifier", "text/plain", HttpStatus.SC_NOT_FOUND);
            respondTo.tell(response, getSelf());
        }
    }

    private void forwardEnrichedMessage(EnrichRegistryStoredQueryResponse msg) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/soap+xml");

        MediatorHTTPRequest request = new MediatorHTTPRequest(
                requestHandler, getSelf(), "xds-b-registry", "POST", "http",
                config.getProperties().getProperty("xds.registry.host"),
                Integer.parseInt(config.getProperties().getProperty("xds.registry.port")),
                config.getProperties().getProperty("xds.registry.path"),
                msg.getEnrichedMessage(),
                headers,
                Collections.<String, String>emptyMap()
        );

        ActorSelection httpConnector = getContext().actorSelection("/user/" + config.getName() + "/http-connector");
        httpConnector.tell(request, getSelf());
    }

    private void finalizeResponse(MediatorHTTPResponse response) {
        respondTo.tell(response.toFinishRequest(), getSelf());
    }

    @Override
    public void onReceive(Object msg) throws Exception {
        if (msg instanceof MediatorHTTPRequest) {
            log.info("Parsing registry stored query request...");
            requestHandler = ((MediatorHTTPRequest) msg).getRequestHandler();
            respondTo = ((MediatorHTTPRequest) msg).getRespondTo();
            parseMessage((MediatorHTTPRequest) msg);
        } else if (msg instanceof ParsedRegistryStoredQuery) {
            log.info("Parsed contents. Resolving patient enterprise identifier...");
            lookupEnterpriseIdentifier(((ParsedRegistryStoredQuery) msg).getPatientId());
        } else if (msg instanceof ResolvePatientIdentifierResponse) {
            enrichEnterpriseIdentifier((ResolvePatientIdentifierResponse) msg);
        } else if (msg instanceof EnrichRegistryStoredQueryResponse) {
            log.info("Sending enriched request to XDS.b Registry");
            forwardEnrichedMessage((EnrichRegistryStoredQueryResponse) msg);
        } else if (msg instanceof MediatorHTTPResponse) {
            log.info("Received response from XDS.b Registry");
            finalizeResponse((MediatorHTTPResponse) msg);
        } else {
            unhandled(msg);
        }
    }
}
