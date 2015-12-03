package com.poco.PoCoRuntime;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Stack;

import dk.brics.automaton.Automaton;

/**
 * Root policy that defers all decisions to its single child Policy. Created so
 * that we can test the code generated by the compiler before its able to
 * parse/codegen tree-defining policies.
 */
public class RootPolicy {
    // private Policy child;
    protected ArrayList<Policy> children = new ArrayList<>();
    private Stack<String> monitoringActions;
    public Stack<String> promotedEvents;
    private Stack<String> monitoringResult;
    private Stack<Object> res4Action;

    private String strategy = "";

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public void addChild(Policy child) {
        this.children.add(child);
    }

    public RootPolicy() {
        this.monitoringActions = new Stack<>();
        this.promotedEvents = new Stack<>();
        this.monitoringResult = new Stack<>();
        this.res4Action = new Stack<>();
    }

    public RootPolicy(Policy child) {
        // this.child = child;
        this.children.add(child);
        this.monitoringActions = new Stack<>();
        this.promotedEvents = new Stack<>();
        this.monitoringResult = new Stack<>();
        this.res4Action = new Stack<>();
    }

    /**
     * AspectJ calls this method on any attempted action.
     *
     * @param event
     *            security-relevant action caught by AspectJ
     * @throws Exception
     */
    public void queryAction(Event event) {
        Result resEvent = null;
        if (event.getEventType() == null || event.getEventType() != "Result") {
            monitoringActions.push(event.getSignature());
        } else {
            resEvent = (Result) event;
            // monitoringActions.push(event.getSignature());
            monitoringResult.push(resEvent.resultStr);
        }

        // 1. clear all policies' last queried results
        clearLastQueriedResults();

        // 2. Query all its children
        ArrayList<SRE> res4ChildrenSREs = queryAllSubPolicies(event);

        // 3. perform the strategy on all the children results
        CalculatedSRE result = null;
        if (strategy != null && strategy.trim().length() > 0)
            result = SREUtil.performStrategy(strategy, new ArrayList<SRE>(
                    res4ChildrenSREs));
        else {
            SRE res = res4ChildrenSREs.get(0);
            if (res != null)
                result = res.convert2CalculatedSre();
        }
        // 4. check if the result is the neutral case, if so then it is the
        // neutral
        // case, directly return control to the queried event
        if (isResultNullorNeutral(result))
            return;

        // 5. if it is not the neutral case, then handle the positive value of
        // the top-level SRE FIRST!!
        if (result.getPosAutomaton() != null) {
            Automaton posResult = result.getPosAutomaton();
			/*
			 * Sub-step 1, for the action case, first check whether the
			 * monitored action is in the set of the positive value of the
			 * top-level SRE. if so, this action should be allowed. In the case
			 * of result case, since only the monitored ACTION will be pushed
			 * onto stack, therefore, it also work for result case.
			 *//*
				 * For example: <Action(a) => +`b'> (<result(b, "11") => +`b' |
				 * <result(b, "11") => +`a'>)
				 */
            if (!monitoringActions.isEmpty()) {
                String monitoringActionSig = monitoringActions.peek();
                if (posResult.run(monitoringActionSig)) {
                    System.out.println(monitoringActions.peek()
                            + " action is allowed to be executed!");
                    monitoringActions.pop();
                    return;
                }
            }

            // Sub-step 2, try to locate the easy-to-find action
            // first sort the list, then iterator the list
            ArrayList<String> sortedList = new ArrayList<String>(
                    result.getConcreteMethods());
            Collections.sort(sortedList);

            for (String str : sortedList) {
                boolean isObjMethodCall = false;
                if (posResult.run(str)) {
                    String[] objInfos = null;
                    // get rid of unnecessary info
                    String methodName = RuntimeUtils.getMethodName(str);
                    String methodArg = RuntimeUtils.getfunArgstr(str);
                    str = methodName + "(" + methodArg + ")";

                    if (RuntimeUtils.isObjCall(methodName)) {
                        objInfos = RuntimeUtils.getObjAddr4Call(methodName);
                        if (objInfos != null
                                && DataWH.address2ObjVal
                                .containsKey(objInfos[0])) {
                            // if the object is null, skip directly
                            if (DataWH.address2ObjVal.get(objInfos[0]) == null)
                                continue;
                            else
                                isObjMethodCall = true;
                        }
                    }
                    // b. if it is a valid method
                    promoteMethod(str, isObjMethodCall, objInfos);
                    if (Promoter.isSuccesslyPromoted()) {
                        Promoter.resetSuccesslyPromoted();
                        return;
                    }else {
                        if(!promotedEvents.isEmpty())
                            promotedEvents.pop();
                    }
                }
            }

            // Sub-step 3: if failed to locate the any positive action, then
            // we should locate positive results before check the negative
            // events;
            // need skip the process() if it is the action case!!!!!

            // a. check if there is a preferred result
            if (!monitoringResult.empty()) {
                String preferedResult = monitoringResult.peek();
                if (posResult.run(preferedResult)) {
                    // System.out.println("The easy-to-locate result will be returned");
                    monitoringResult.pop();
                    // this case can direct return, due to the fact that the
                    // action has been proceeded already, therefore, no need
                    // to check the negative result
                    return;
                }
                // b. failed to located the preferred result, check the list
                // of the concrete result
                else {
                    sortedList = new ArrayList<String>(
                            result.getConcreteResults());
                    Collections.sort(sortedList);
                    for (String str : sortedList) {
                        if (posResult.run(str)) {
                            Object newObj = genObjFrmConcreteRes(str);
                            if (newObj != null) {
                                resEvent.setResult(newObj);
                                event = resEvent;
                                return;
                            } else
                                continue;
                        }
                    }
                }
            } else {
                // this case will be the case that the proceed() need to be
                // skipped. Moreover, since it is an action case, we do need
                // need to check the preferred result (because it is not
                // existed). Therefore, we just need to check the list
                // of the concrete result
                sortedList = new ArrayList<String>(result.getConcreteResults());
                Collections.sort(sortedList);
                for (String str : sortedList) {
                    if (posResult.run(str)) {
                        Object newObj = genObjFrmConcreteRes(str);
                        if (newObj != null) {
                            res4Action.push(newObj);
                            return;
                        } else
                            continue;
                    }
                }
            }
        }

        // end of handling the positive value of top-level SRE
        // ==================================================================

        // 6. if failed to locate any easy-to-find action or result, then
        // need check the negative value of the top-level SRE.
        if (result.getNegAutomaton() == null)
            return;
        else {
            // if already on stack, show System.exit(-1);
            Automaton negResult = result.getNegAutomaton();
            if (!monitoringActions.empty()) {
                String monitoringActionSig = monitoringActions.peek();
                if (negResult.run(monitoringActionSig)) {
                    System.out.println(monitoringActions.peek()
                            + " action is not allowed to be executed!");
                    System.out.println("System will exit!");
                    monitoringActions.pop();
                    System.exit(-1);
                } else
                    return;
            } else
                return;
        }
    }

    private Object genObjFrmConcreteRes(String str) {
        if (RuntimeUtils.isPoCoObject(str)) {
            String objTyp = RuntimeUtils.getPoCoObjTyp(str);
            String objVal = RuntimeUtils.getPoCoObjVal(str);
            if (isPrimitiveType(objTyp))
                return genNewResult(objTyp, objVal);
            else
                return getDatafromAddress(objVal);
        }
        return null;
    }

    private void promoteMethod(String str, boolean isObjMethodCall,
                               String[] objInfos) {
        try {
            String strParams = RuntimeUtils.getfunArgstr(str);
            Object[] obj4Args = null;
            Object obj4ObjCall = null;

            if (isObjMethodCall) {
                obj4ObjCall = DataWH.address2ObjVal.get(objInfos[0]);
                String objClass = obj4ObjCall.getClass().getName();
                // .classA.classB.classC
                if (objInfos[1] != null) {
                    String[] fields = objInfos[1].substring(1).split(".");
                    for (String field : fields) {
                        Field fld = obj4ObjCall.getClass().getDeclaredField(field);
                        obj4ObjCall = fld.get(obj4ObjCall);
                        if (obj4ObjCall == null)
                            break;
                        else {
                            objClass = obj4ObjCall.getClass().getName();
                        }
                    }
                }
                if(obj4ObjCall == null)
                    return;
                else
                    // will need to handle the objInfos[1]....
                    str = RuntimeUtils.concatClsMethod(objClass, objInfos[2]) + "("
                            + strParams + ")";
            }
            promotedEvents.push(str);
            if (strParams != null && strParams.trim().length() > 0) {
                String[] paramStrs = strParams.split(",");
                obj4Args = new Object[paramStrs.length];
                for (int i = 0; i < paramStrs.length; i++) {
                    obj4Args[i] = getObj(paramStrs[i]);
                }
            }
            Promoter.Reflect(obj4ObjCall, str, obj4Args,objInfos);

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private ArrayList<SRE> queryAllSubPolicies(Event event) {
        ArrayList<SRE> res4ChildrenSREs = new ArrayList<SRE>();
        for (int i = 0; i < children.size(); i++) {
            if (children.get(i).accepts(event)) {
                SRE sre = children.get(i).query(event);
                res4ChildrenSREs.add(sre);
            } else
                res4ChildrenSREs.add(new SRE(null, null));
        }
        return res4ChildrenSREs;
    }

    private void clearLastQueriedResults() {
        for (int i = 0; i < children.size(); i++) {
            children.get(i).clearIsQueried();
        }
    }

    private boolean isResultNullorNeutral(CalculatedSRE sreVal) {
        return (sreVal == null || (sreVal.getPosAutomaton() == null && sreVal
                .getNegAutomaton() == null));
    }

    private Object getObj(String objStr) {
        Object returnObj = null;
        if (RuntimeUtils.isPoCoObject(objStr)) {
            String objTyp = RuntimeUtils.getPoCoObjTyp(objStr);
            String objVal = RuntimeUtils.getPoCoObjVal(objStr);
            if (isPrimitiveType(objTyp)) {
                returnObj = genNewResult(objTyp, objVal);
            } else {
                returnObj = getDatafromAddress(objVal);
            }
        } else {
            // if(DataWH.dataVal.containsKey(objStr.substring(1)))
            returnObj = DataWH.dataVal.get(objStr.substring(1)).getObj();
        }
        return returnObj;
    }

    private Object getDatafromAddress(String val) {
        if (DataWH.address2ObjVal.containsKey(val))
            return DataWH.address2ObjVal.get(val);

        return null;
    }

    private boolean isPrimitiveType(String type) {
        switch (type) {
            case "int":
            case "Integer":
            case "long":
            case "double":
            case "float":
            case "boolean":
            case "char":
            case "String":
            case "java.lang.String":
                return true;
            default:
                return false;
        }
    }

    private Object genNewResult(String type, String value) {
        Object retObj = null;
        switch (type) {
            case "int":
            case "Integer":
                retObj = new Integer(value);
                break;
            case "long":
                retObj = new Long(value);
                break;
            case "double":
                retObj = new Double(value);
                break;
            case "float":
                retObj = new Float(value);
                break;
            case "boolean":
                retObj = new Boolean(value);
                break;
            case "char":
                retObj = new Character(value.charAt(0));
                break;
            default:
                retObj = new String(value);
        }
        return retObj;
    }

    /**
     * This method is used to handle the case where the sre is start with '$',
     * it has two sub-cases. 1. it is an objMethodCall 2. it is a variable, then
     * load the variable val
     */
    private String handleObjCall(String reStr) {
        String methodSignature = null;

        String varName = reStr.substring(1);
        String[] vals = RuntimeUtils.objMethodCall(varName);

        if (vals != null) {
            // isObjMethodCall = true;
            methodSignature = DataWH.dataVal.get(vals[0]).getType().toString()
                    + "." + vals[1] + "(" + vals[2] + ")";
            methodSignature = RuntimeUtils.getMethodSignature(methodSignature);
        } else if (DataWH.dataVal.containsKey(varName)
                && DataWH.dataVal.get(varName).getObj() != null) {
            // the case of variable value is an method string
            methodSignature = DataWH.dataVal.get(varName).getObj().toString();
        } else
            return null;

        return methodSignature;
    }

    public boolean hasRes4Action() {
        return !this.res4Action.isEmpty();
    }

    public Object getRes4Action() {
        if (!this.res4Action.isEmpty()) {
            Object obj = this.res4Action.peek();
            this.res4Action.pop();
            return obj;
        }
        return null;
    }
}