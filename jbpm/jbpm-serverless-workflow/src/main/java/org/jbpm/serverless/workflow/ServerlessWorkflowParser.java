/*
 * Copyright 2019 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jbpm.serverless.workflow;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.drools.compiler.rule.builder.dialect.java.JavaDialect;
import org.drools.core.util.StringUtils;
import org.jbpm.process.core.context.variable.VariableScope;
import org.jbpm.process.core.validation.ProcessValidationError;
import org.jbpm.ruleflow.core.RuleFlowProcess;
import org.jbpm.ruleflow.core.validation.RuleFlowProcessValidator;
import org.jbpm.serverless.workflow.api.interfaces.State;
import org.jbpm.serverless.workflow.api.mapper.JsonObjectMapper;
import org.jbpm.workflow.core.NodeContainer;
import org.jbpm.workflow.core.impl.ConnectionImpl;
import org.jbpm.workflow.core.impl.DroolsConsequenceAction;
import org.jbpm.workflow.core.node.ActionNode;
import org.jbpm.workflow.core.node.CompositeContextNode;
import org.jbpm.workflow.core.node.EndNode;
import org.jbpm.workflow.core.node.StartNode;
import org.kie.api.definition.process.Connection;
import org.kie.api.definition.process.Node;
import org.kie.api.definition.process.Process;
import org.jbpm.serverless.workflow.api.Workflow;
import org.jbpm.serverless.workflow.api.actions.Action;
import org.jbpm.serverless.workflow.api.states.DefaultState.Type;
import org.jbpm.serverless.workflow.api.states.OperationState;

/**
 * Serverless Workflow specification parser to produce list
 *
 */
public class ServerlessWorkflowParser {
    
    private AtomicLong idCounter = new AtomicLong(1);
    
    public Process parseWorkFlow(Reader workflowFile) throws Exception {
        JsonObjectMapper mapper = new JsonObjectMapper();

        Workflow workflow = mapper.readValue(readWorkflowSource(workflowFile), Workflow.class);
        
        Map<String, Long> nameToNodeId = new HashMap<>();
        
        
        RuleFlowProcess process = new RuleFlowProcess();
        process.setId(workflow.getId());
        process.setAutoComplete(true);
        process.setName(workflow.getName());
        process.setVersion(workflow.getVersion());
        process.setPackageName("org.kie.kogito");
        process.setVisibility(RuleFlowProcess.PUBLIC_VISIBILITY);
        
        StartNode startNode = startNode(process);
        
        for (State state : workflow.getStates()) {
            
            if (state.getType().equals(Type.OPERATION)) {
                OperationState operationState = (OperationState) state;
                
                List<Action> actions = operationState.getActions();
                
                CompositeContextNode embedded = compositeContextNode(state.getName(), process); 
                
                if (actions != null && !actions.isEmpty()) {
                    
                    StartNode embeddedStartNode = startNode(embedded);
                    Node start = embeddedStartNode;
                    Node current = null;
                    for (Action action : actions) {
                        
                        if ("script".equalsIgnoreCase(action.getFunction().getType())) {
                            current = scriptNode(action.getFunction().getName(), action.getFunction().getParameters().get("script"), embedded);
                            
                            connection(start.getId(), current.getId(), start.getId() + "_" + current.getId(), embedded);
                            start = current;
                        }
                                                
                    }
                    
                    EndNode embeddedEndNode = endNode(true, embedded);
                    connection(current.getId(), embeddedEndNode.getId(), current.getId() + "_" + embeddedEndNode.getId(), embedded);
                }
                if (state.getName().equals(workflow.getStartsAt())) {
                    connection(startNode.getId(), embedded.getId(), startNode.getId() + "_" + embedded.getId(), process);
                }
                
                if (state.isEnd()) {
                    EndNode endNode = endNode(true, process);
                    
                    connection(embedded.getId(), endNode.getId(), embedded.getId() + "_" + endNode.getId(), process);
                }
                
                nameToNodeId.put(state.getName(), embedded.getId());
            }
        }
        
        // link states
        workflow.getStates().stream().filter(state -> state instanceof OperationState).forEach(state -> {
            if (((OperationState)state).getNextState() != null) {
                
                Long sourceId = nameToNodeId.get(state.getName());
                Long targetId = nameToNodeId.get(((OperationState)state).getNextState());
                
                connection(sourceId, targetId, sourceId + "_" + targetId, process);
            }
        });
        
        validate(process);
        
        return process;
    }

    protected String readWorkflowSource(Reader reader) throws FileNotFoundException {
        return StringUtils.readFileAsString(reader);
    }

    protected String readWorkflowSource(File location) throws FileNotFoundException {
        return StringUtils.readFileAsString(new InputStreamReader(new FileInputStream(location)));
    }
    
    protected String readWorkflowSource(String location) {
        return StringUtils.readFileAsString(new InputStreamReader(this.getClass().getResourceAsStream(location)));
    }
    
    protected StartNode startNode(NodeContainer nodeContainer) {
        StartNode startNode = new StartNode();
        startNode.setId(idCounter.getAndIncrement());
        startNode.setName("start node");
        
        nodeContainer.addNode(startNode);
        
        return startNode;
    }
    
    protected EndNode endNode(boolean terminate, NodeContainer nodeContainer) {
        EndNode endNode = new EndNode();
        endNode.setId(idCounter.getAndIncrement());
        endNode.setName("end node");
        
        nodeContainer.addNode(endNode);
        
        return endNode;
    }
    
    protected ActionNode scriptNode(String name, String script, NodeContainer nodeContainer) {
        ActionNode scriptNode = new ActionNode();
        scriptNode.setId(idCounter.getAndIncrement());
        scriptNode.setName(name);
        
        scriptNode.setAction(new DroolsConsequenceAction());        
        ((DroolsConsequenceAction)scriptNode.getAction()).setConsequence(script);
        ((DroolsConsequenceAction)scriptNode.getAction()).setDialect(JavaDialect.ID);
        
        nodeContainer.addNode(scriptNode);
        
        return scriptNode;
    }
    
    protected CompositeContextNode compositeContextNode(String name, NodeContainer nodeContainer) {
        CompositeContextNode subProcessNode = new CompositeContextNode();
        subProcessNode.setId(idCounter.getAndIncrement());
        subProcessNode.setName(name);
        VariableScope variableScope = new VariableScope();
        subProcessNode.addContext(variableScope);
        subProcessNode.setDefaultContext(variableScope);
        subProcessNode.setAutoComplete(true);
        
        nodeContainer.addNode(subProcessNode);
        
        return subProcessNode;
    }
    
    protected Connection connection(long fromId, long toId, String uniqueId, NodeContainer nodeContainer) {
        Node from = nodeContainer.getNode(fromId);
        Node to = nodeContainer.getNode(toId);
        ConnectionImpl connection = new ConnectionImpl(
            from, org.jbpm.workflow.core.Node.CONNECTION_DEFAULT_TYPE,
            to, org.jbpm.workflow.core.Node.CONNECTION_DEFAULT_TYPE);
        connection.setMetaData("UniqueId", uniqueId);
        return connection;
    }
    
    protected RuleFlowProcess validate(RuleFlowProcess process) {
        ProcessValidationError[] errors = RuleFlowProcessValidator.getInstance().validateProcess(process);
        for (ProcessValidationError error : errors) {
            System.out.println(error.toString());
        }
        if (errors.length > 0) {
            throw new RuntimeException("Process could not be validated !");
        }
        return process;
    }
}
