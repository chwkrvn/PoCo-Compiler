package com.poco;

import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Root policy that defers all decisions to its single child Polipocy. Created
 * so that we can test the code generated by the compiler before its able to
 * parse/codegen tree-defining policies.
 */
public class DummyRootPolicy {
	private Stack<String> monitoringEvents;
	private Policy child;

	public Stack<String> promotedEvents;

	public DummyRootPolicy(Policy child) {
		this.child = child;
		this.monitoringEvents = new Stack<>();
		this.promotedEvents = new Stack<>();
	}

	public void setChild(Policy child) {
		this.child = child;
	}

	/**
	 * AspectJ calls this method on any attempted action.
	 * 
	 * @param event
	 *            security-relevant action caught by AspectJ
	 * @throws Exception
	 */
	public void queryAction(Event event) {
		if (event.getEventType() == null || event.getEventType() != "Result") {
			monitoringEvents.push(event.getSignature());
		}
		SRE result = child.query(event);
		// when accept is false, the returned SRE value is NULL
		if (result == null) {
			System.exit(-1);
			// return;
		}

		boolean posMatch = false;
		boolean negMatch = false;

		//System.out.format("Root policy queried with event: \"%s\"\n",event.getSignature());
		if (result.getPositiveRE() != null && !result.getPositiveRE().equals("null")) {
			posMatch = true;
			//System.out.format("Child policy returned +`%s'\n",result.positiveRE());
		}
		if (result.getNegativeRE() != null) {
			if (!posMatch)
				negMatch = true;
			 //System.out.format("Child policy returned -`%s'\n",result.negativeRE());
		}
		// Neutral case which means should be okay if
		if (result.getPositiveRE() == null && result.getNegativeRE() == null) {
			 System.out.println("Child policy returned Neutral");
		}
		
		if (posMatch) {
			//$$AddBCC$$(#javax.mail.Message{$$msg$$},#java.lang.String{domain})
			//com.poco.RuntimeDemo.ShowDialog(#java.lang.String{$$Attachments_message})
			String resultPos = result.positiveRE();
			String reg = "(.*)(\\$\\$(.+)\\$\\$)(.*)";
			Pattern pattern = Pattern.compile(reg);
			Matcher matcher;

			// first find the number of the arguments
			int numOfArgs = 0;
			String[] paramStrs = null;
			Object[] obj4Args = null;
			int lParen = resultPos.indexOf('(');
			int rParen = resultPos.indexOf(')');
			// get the parameter part of the string
			if (lParen != -1 && rParen != -1 && rParen > lParen) {
				if (resultPos.substring(lParen + 1, rParen).length() > 0)
					paramStrs = resultPos.substring(lParen + 1, rParen).split(
							",");
			}
			if (paramStrs != null && paramStrs.length > 0) {
				obj4Args = new Object[paramStrs.length];
				// arg: #javax.mail.Message{$$msg}; arg:
				// #java.lang.String{domain}
				for (int i = 0; i < paramStrs.length; i++) {
					String value = paramStrs[i];
					int leftIndex  = value.indexOf('{');
					int rightIndex = value.indexOf('}');
					if (leftIndex != -1 && rightIndex != -1 && rightIndex>leftIndex)
						value = value.substring(leftIndex+1, rightIndex);
					if (value != null && value.length() > 0) {
						matcher = pattern.matcher(value);
						if (matcher.find()) {// it is variable (e.g., $$msg)
							if (DataWH.dataVal.get(matcher.group(3).trim())
									.getType().equals("java.lang.String")) {
								String str = DataWH.dataVal
										.get(matcher.group(3).trim()).getObj()
										.toString();
								matcher = pattern.matcher(str);
								while (matcher.find()) {
									String id = matcher.group(3).trim();
									String val = DataWH.closure.get(id);
									if (val != null) {
										str = str.replace(matcher.group(2), val);
										matcher = pattern.matcher(str);
									} else
										break;
								}
								obj4Args[i] = new String(str);
							} else
								obj4Args[i] = DataWH.dataVal.get(matcher.group(3).trim()).getObj();
							
						} else {
							String regex = "#(.+)\\{(.+)\\}";
							Pattern patternType = Pattern.compile(regex);
							Matcher matcherType = patternType
									.matcher(paramStrs[i]);
							if (matcherType.find()) {
								// need add more cases
								switch (matcherType.group(1).trim()) {
								case "String":
								case "java.lang.String":
									obj4Args[i] = new String(value);
									break;
								case "Integer":
									obj4Args[i] = new Integer(value);
									break;
								default:
									obj4Args[i] = new String(value);
									break;
								}
							}
						}
					}
				}
			}
			
			matcher = pattern.matcher(resultPos);
			boolean needUpdate = matcher.find();
			while (needUpdate) {
				// need delete (, otherwise cause issues.
				String replaceStr = DataWH.closure.get(matcher.group(3).trim());
				if (replaceStr.indexOf('(') != -1)
					replaceStr = replaceStr.substring(0,
							replaceStr.indexOf('('));
				resultPos = resultPos.replace(matcher.group(2).trim(),
						replaceStr);
				matcher = pattern.matcher(resultPos);
				needUpdate = matcher.find();
			}
			boolean promoted = false;
			// have to manipulate the string for new case, since signature will
			// not include new keyword
			if (resultPos.substring(resultPos.length() - 4, resultPos.length())
					.equals(".new"))
				resultPos = resultPos.substring(0, resultPos.length() - 4);
			
			if(monitoringEvents.isEmpty() && resultPos!=null)
				promoted =false;
			else if (methodMatch(monitoringEvents.peek(), resultPos)) {
				promoted = true;
			}
			if (promoted) {
				System.out.println("the action " + monitoringEvents.peek()
						+ " will be allowed!");
				monitoringEvents.pop();
			} else {
				try {
					String methodname = resultPos;
					int index = resultPos.indexOf('(');
					if (index > -1)
						methodname = resultPos.substring(0, index);
					promotedEvents.push(methodname.trim());
					Promoter.Reflect(resultPos, obj4Args);
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
		}

		if (negMatch) {
			// if already on stack, show System.exit(-1);
			if (! monitoringEvents.empty()
					&& monitoringEvents.peek().contains(
							result.negativeRE().toString())) {
				monitoringEvents.pop();
			}
		}

		if (posMatch)
			return;
		if (negMatch)
			System.exit(-1);
	}

	/**
	 * This method used to matching the monitored method name to the resultPos
	 * the issue is that the event.signature only record the partial of the
	 * parameter typ (e.g., Message instead of javax.mail.Message), so need
	 * compare method name then parameter types one by one
	 * 
	 * @param peek
	 * @param resultPos
	 * @return
	 */
	private boolean methodMatch(String peek, String resultPos) {
		String reg = "(.+)\\((.*)\\)";
		String peekMethodName = peek;
		String resultMethodName = resultPos;
		String[] peekParams = null;
		String[] resultParams = null;

		Pattern pattern = Pattern.compile(reg);
		Matcher matcher1 = pattern.matcher(peek);
		Matcher matcher2 = pattern.matcher(resultPos);
		if (matcher1.find()) {
			peekMethodName = matcher1.group(1).trim();
			if (peekMethodName.split(" ").length == 2)
				peekMethodName = peekMethodName.split(" ")[1];
			String strParams = matcher1.group(2).trim();
			if(strParams.length() ==0)  
				peekParams = null;
			else
				peekParams = strParams.split(",");
		}
		if (matcher2.find()) {
			resultMethodName = matcher2.group(1).trim();
			if (resultMethodName.split(" ").length == 2)
				resultMethodName = resultMethodName.split(" ")[1];
			String strParams = matcher2.group(2).trim();
			if(strParams.length() ==0)  
				resultParams = null;
			else
				resultParams = strParams.split(",");

		}
		
		
		if (peekMethodName.equals(resultMethodName)) {
			if (resultParams == null) {
				return true;
			}else {
				if (resultParams.length == peekParams.length) {
					boolean match = true;
					for (int i = 0; i < resultParams.length; i++) {
						if (!resultParams[i].contains(peekParams[i])) {
							match = false;
							break;
						}
					}
					return match;
				}

			}
		} 
		return false;
	}
}
