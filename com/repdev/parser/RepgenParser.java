package com.repdev.parser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

import com.repdev.DatabaseLayout;
import com.repdev.ErrorCheckResult;
import com.repdev.FileType;
import com.repdev.RepDevMain;
import com.repdev.SpecialVariables;
import com.repdev.SymitarFile;
import com.repdev.DatabaseLayout.DataType;
import com.repdev.DatabaseLayout.Field;
import com.repdev.DatabaseLayout.Record;

public class RepgenParser {
	private StyledText txt;
	private SymitarFile file;
	private int sym;
	private boolean reparse = true;
	
	private static HashSet<String> functions, keywords;
	
	private static DatabaseLayout db;
	private static SpecialVariables specialvars = SpecialVariables.getInstance();
	
	private ArrayList<Token> ltokens = new ArrayList<Token>();
	private ArrayList<Variable> lvars = new ArrayList<Variable>();
	private ArrayList<Token> lasttokens = new ArrayList<Token>();
	private ArrayList<Include> includes = new ArrayList<Include>();
	
	private ArrayList<Error> errorList = new ArrayList<Error>();
	private ArrayList<Task> taskList = new ArrayList<Task>();
	
	boolean refreshIncludes = true;
	
	static {
		functions = build(new File("functions.txt"));
		keywords = build(new File("keywords.txt"));
		db = DatabaseLayout.getInstance();
	}
	
	

	public RepgenParser(StyledText txt, SymitarFile file, int sym) {
		this.txt = txt;
		this.file = file;
		this.sym = sym;
	}
	





	
	/**
	 * Worker class for loading any included files and parsing out their contents
	 * Currently runs once at opening of the report
	 * @author poznanja
	 *
	 */
	public class BackgroundIncludeParser extends Thread{
		String text;
		
		public BackgroundIncludeParser(String text){
			super("Background Include Parser");
			this.text = text;
		}
		
		public void run(){
			String data = "";
			ArrayList<Token> tokens = new ArrayList<Token>();
			boolean exists = false;
			
			//TODO: PARSE VARIABLES IN LEVELS DEEPER THAN FIRST.
			//Probably change it to have this function get and parse all the files, then make rebuildVars go off a fileName and token array
			for( Token tok : ltokens ){
				if( tok.getStr().equals("#include") && tok.getAfter() != null && tok.inDefs()){
					String fileName = getFullString(tok.getAfter(),text);
					
					exists = false;
					
					for( Include cur : includes ){
						if( cur.getDivision().equals( Division.DEFINE) && cur.getFileName().equals(fileName))
							exists=true;
					}
					
					if( !exists ){
						includes.add(new Include(fileName,Division.DEFINE));
	
						data = RepDevMain.SYMITAR_SESSIONS.get(sym).getFile(new SymitarFile(fileName,FileType.REPGEN));				
				
						if( data == null )
							return;
						
						parse(fileName, data, 0, data.length(), 0, tokens, new ArrayList<Variable>(),null);
						
						//Set to be in defs, since we are assuming only working with Include files already in the define division
						for( Token cur : tokens)
							cur.setInDefs(true);
					
						rebuildVars(fileName,data,tokens);
					}
				}
			}
		}
	}
	
	/**
	 * Warning: Doesn't save symitar file
	 * 
	 * Can be called to validate
	 * 
	 * @author poznanja
	 * 
	 */
	public class BackgroundSymitarErrorChecker extends Thread {
		RepgenParser me;

		public BackgroundSymitarErrorChecker(RepgenParser instance) {
			super("Background Error Checker");
			me = instance;
		}

		public void run() {
			final Table tblErrors = RepDevMain.mainShell.getErrorTable();

			if (tblErrors.isDisposed())
				return;

			try {
				Display display = tblErrors.getDisplay();

				// Remove old errors
				errorList.clear();

				// Error check with symitar
				errorList.add(new Error(file.getName(),RepDevMain.SYMITAR_SESSIONS.get(sym).errorCheckRepGen(file.getName())));

				// Variable checking
				 for (final Variable var : lvars) {
					if (var.getFilename().equals(file.getName())) {
						int count = 0;

						for (Variable var2 : lvars)
							if (var2.equals(var))
								count++;

						if (count > 1 && !tblErrors.isDisposed())
							display.syncExec(new Runnable() {
								public void run() {
									if (!txt.isDisposed())
										errorList.add(new Error(file.getName(), "Duplicate variable name: " + var.getName(), txt.getLineAtOffset(var.getPos()), var.getPos() - txt.getOffsetAtLine(txt.getLineAtOffset(var.getPos())),Error.Type.WARNING));
								}
							});
					}
				}
				 

				// Add to list
				display.asyncExec(new Runnable() {
					public void run() {
						if (!tblErrors.isDisposed()) {
							for (TableItem item : tblErrors.getItems()) {
								if (((SymitarFile) item.getData("file")).equals(file) && ((Integer) item.getData("sym")) == sym)
									item.dispose();

							}

							for (Error error : errorList) {
								if (!error.getDescription().trim().equals("")) {
									TableItem row = new TableItem(tblErrors, SWT.NONE);
									row.setText(0, error.getDescription());
									row.setText(1, error.getFile());

									if (error.getLine() >= 0 && error.getCol() >= 0)
										row.setText(2, String.valueOf(error.getLine()) + " : " + error.getCol());
									else
										row.setText(2, "---");

									row.setData("file", file);
									row.setData("sym", sym);
									row.setData("error", error);
									
									if( error.getType() == Error.Type.SYMITAR_ERROR )
										row.setImage(RepDevMain.smallErrorsImage);
									else
										row.setImage(RepDevMain.smallWarningImage);
								}
							}

						}
					}
				});
			} catch (Exception e) {
				//Just ignore if anything happens to our UI while we are error checking.
			}

		}
	}

	
	/**
	 * Adds tokens to ltokens, but also keeps track of it in the new tokens list
	 * for determining what to process later
	 * 
	 * @param spot
	 * @param tok
	 */
	private void addToken(ArrayList<Token> tokens, int spot, Token tok){
		tokens.add(spot,tok);
		lasttokens.add(tok);
	}

	/**
	 * Parses given file and data into tokens, also uses current token lists and usually only replaces certain sections of the
	 * token list.
	 * 
	 * Doesn't do any error checking, or anything like that
	 * 
	 * @param filename
	 * @param str
	 * @param start
	 * @param end
	 * @param oldend
	 * @param tokens
	 * @param vars
	 * @param txt
	 * @return
	 */
	private boolean parse(String filename, String str, int start, int end, int oldend, ArrayList<Token> tokens, ArrayList<Variable> vars, StyledText txt) {
		Token modToken = null;

		lasttokens.clear();
		
		boolean allDefs = true;

		if (str.length() == 0)
			return false;

		int ftoken;
		for (ftoken = 0; ftoken < tokens.size(); ftoken++)
			if (tokens.get(ftoken).getEnd() >= start)
				break;

		int ltoken;
		for (ltoken = ftoken; ltoken < tokens.size(); ltoken++)
			if (tokens.get(ltoken).getStart() > oldend)
				break;

		if (tokens.size() > 0) {
			// Must scan several more ahead for records with spaces
			ltoken = Math.min(tokens.size() - 1, ltoken + 3);

			// And two back for editing the second "word" in a record, and for
			// fields with colons
			ftoken = Math.max(0, ftoken - 2);

			boolean inStr;

			inStr = tokens.get(ltoken).inString();

			while (tokens.get(ltoken).inString() == inStr) {
				ltoken++;

				if (ltoken > tokens.size() - 1) {
					ltoken = tokens.size() - 1;
					break;
				}
			}

		}

		int charStart, charEnd;

		if (ftoken < tokens.size())
			charStart = Math.min(start, tokens.get(ftoken).getStart());
		else
			charStart = start;

		if (ltoken < tokens.size())
			charEnd = Math.min(str.length(), Math.max(end, tokens.get(ltoken).getStart() + end - oldend + tokens.get(ltoken).getStr().length()));
		else
			charEnd = str.length();

		char[] chars = str.substring(charStart, charEnd).toLowerCase().toCharArray();

		boolean inString = false, inDate = false, inDefine = false;
		int commentDepth = 0;
		if (ftoken > 0) {
			inString = tokens.get(ftoken - 1).inString();
			inDate = tokens.get(ftoken - 1).getEndInDate();
			commentDepth = tokens.get(ftoken - 1).getEndCDepth();
			inDefine = tokens.get(ftoken - 1).inDefs();
		}

		boolean oldInString = false, oldInDefine = false, oldInDate = false;
		int oldCommentDepth = 0;
		if (ltoken < tokens.size()) {
			oldInString = tokens.get(ltoken).inString();
			oldInDate = tokens.get(ltoken).inDate();
			oldCommentDepth = tokens.get(ltoken).getCDepth();
			oldInDefine = tokens.get(ltoken).inDefs();
		}

		if (inDefine || oldInDefine) {
			reparseAll();
			return false;
		}

		if (tokens.size() > 0)
			for (int i = 0; i <= ltoken - ftoken; i++)
				tokens.remove(ftoken);

		int curspot = ftoken, cstart = charStart;
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < chars.length; i++) {
			char cur = chars[i];

			if ((cur >= 'a' && cur <= 'z') || (cur >= '0' && cur <= '9') || cur == '#' || cur == '@') {
				sb.append(cur);
			} else {
				String scur = null;

				// Look ahead one name to see if a record exists with this name
				// and make it all one token
				if (cur == ' ') {
					String tempRecord = sb.toString() + " ";
					int x;

					for (x = Math.min(i + 1,chars.length-1); x < chars.length; x++) {
						tempRecord += chars[x];

						if (!((chars[x] >= 'a' && chars[x] <= 'z') || (chars[x] >= '0' && chars[x] <= '9') || chars[x] == '#' || chars[x] == '@')) {
							tempRecord = tempRecord.substring(0, tempRecord.length() - 1);
							break;
						}
					}

					if (db.containsRecordName(tempRecord)) {
						scur = tempRecord;
						i = x;
						
						if( i == chars.length )
							i--;
						
						cur = chars[i];
					}

				}

				// Look ahead again for field names with colons
				if (cur == ':') {
					String tempRecord = sb.toString() + ":";
					int x;

					for (x = i + 1; x < chars.length; x++) {
						tempRecord += chars[x];

						if (!((chars[x] >= 'a' && chars[x] <= 'z') || (chars[x] >= '0' && chars[x] <= '9') || chars[x] == '#' || chars[x] == '@')) {
							tempRecord = tempRecord.substring(0, tempRecord.length() - 1);
							break;
						}
					}

					if (db.containsFieldName(tempRecord)) {
						scur = tempRecord;
						i = x;
						cur = chars[i];
					}

				}

				if (scur == null)
					scur = sb.toString().trim();

				if (scur.length() > 0) {
					if (commentDepth == 0 && !inString) {
						if (scur.equals("define")) {
							inDefine = true;
						} else if (scur.equals("end")) {
							inDefine = false;
							allDefs = false;
						}
					}

					Token nToken = new Token(scur, cstart, commentDepth, commentDepth, inString, inString, inDefine, inDate, inDate);
					addToken(tokens, curspot, nToken);
					
					curspot++;
				}

				sb = new StringBuilder();
				cstart = i + charStart;

				scur = "" + cur;
				if (commentDepth == 0 && cur == '"') {
					if (inString)
						addToken(tokens, curspot, new Token(scur, cstart, 0, 0, true, false, inDefine, inDate, inDate));
					else
						addToken(tokens, curspot, new Token(scur, cstart, 0, 0, true, true, inDefine, inDate, inDate));

					curspot++;
					inString = !inString;
				} else if (!inString && cur == '[') {
					commentDepth++;
					addToken(tokens, curspot, new Token(scur, cstart, commentDepth, commentDepth, false, false, inDefine, inDate, inDate));
					curspot++;
				} else if (!inString && cur == ']') {
					commentDepth--;
					if (commentDepth < 0)
						commentDepth = 0;

					addToken(tokens, curspot, new Token(scur, cstart, commentDepth + 1, commentDepth, false, false, inDefine, inDate, inDate));
					curspot++;
				} else if (commentDepth == 0 && !inString && cur == '\'') {
					if (inDate)
						addToken(tokens, curspot, new Token(scur, cstart, 0, 0, inString, inString, inDefine, true, false));
					else
						addToken(tokens, curspot, new Token(scur, cstart, 0, 0, inString, inString, inDefine, true, true));

					curspot++;
					inDate = !inDate;
				} else if (scur.trim().length() != 0) {
					addToken(tokens, curspot, new Token(scur, cstart, commentDepth, commentDepth, inString, inString, inDefine, inDate, inDate));
					curspot++;
				}

				cstart++;
			}
		}

		String scur = sb.toString().trim();
		if (scur.length() > 0) {
			if (commentDepth == 0 && !inString) {
				if (scur.equals("define")) {
					inDefine = true;
				} else if (scur.equals("end")) {
					inDefine = false;
					allDefs = false;
				}
			}
			modToken = new Token(scur, cstart, commentDepth, commentDepth, inString, inString, inDefine, inDate, inDate);
			modToken.setNearTokens(tokens, curspot);

			addToken(tokens, curspot, modToken);
			curspot++;
		}

		if (end != oldend)
			for (int i = curspot; i < tokens.size(); i++)
				tokens.get(i).incStart(end - oldend);

		int fixspot = curspot;

		if (inString != oldInString || commentDepth != oldCommentDepth || inDefine != oldInDefine || inDate != oldInDate) {
			for (fixspot = curspot; fixspot < tokens.size(); fixspot++) {
				Token tcur = tokens.get(fixspot);
				String cur = tcur.getStr();

				oldInString = tcur.inString();
				oldInDate = tcur.inDate();
				oldCommentDepth = tcur.getCDepth();
				oldInDefine = tcur.inDefs();

				tcur.setInString(inString, inString);
				tcur.setInDate(inDate, inDate);
				tcur.setCDepth(commentDepth, commentDepth);
				tcur.setInDefs(inDefine);

				if (commentDepth == 0 && cur.equals("\"")) {
					if (inString)
						tcur.setInString(true, false);
					else
						tcur.setInString(true, true);

					inString = !inString;
				} else if (!inString && cur.equals("[")) {
					commentDepth++;

					tcur.setCDepth(commentDepth, commentDepth);
				} else if (!inString && cur.equals("]")) {
					commentDepth--;
					if (commentDepth < 0)
						commentDepth = 0;

					tcur.setCDepth(commentDepth + 1, commentDepth);
				} else if (!inString && commentDepth == 0 && cur.equals("define")) {
					inDefine = true;
					tcur.setInDefs(true);
				} else if (!inString && commentDepth == 0 && cur.equals("end")) {
					inDefine = false;
					tcur.setInDefs(false);
				} else if (!inString && commentDepth == 0 && cur.equals("'")) {
					if (inDate)
						tcur.setInDate(true, false);
					else
						tcur.setInDate(true, true);

					inDate = !inDate;
				} else if (inDefine == oldInDefine && commentDepth == oldCommentDepth && inString == oldInString && inDate == oldInDate) {
					break;
				}
			}
		}

		// Go forward 2 tokens to redraw field, if this is a record
		if (modToken != null && !modToken.inDate() && !modToken.inString() && modToken.getCDepth() == 0 && modToken.getAfter() != null && modToken.getAfter().getBefore() != null && modToken.getAfter().getBefore().getStr().equals(":")) {
			fixspot = Math.min(Math.max(fixspot, curspot + 2), tokens.size());
		}

		for (int i = curspot - 1; i >= 0; i--)
			if (!tokens.get(i).inString() && tokens.get(i).getCDepth() != 0) {
				tokens.get(i).setNearTokens(tokens, i);
				break;
			}

		for (int i = ftoken; i < fixspot; i++)
			tokens.get(i).setNearTokens(tokens, i);

		if (tokens.size() > 1) {
			if (ftoken < tokens.size())
				charStart = tokens.get(ftoken).getStart();
			else
				charStart = tokens.get(ftoken - 1).getEnd();

			if (fixspot < tokens.size())
				charEnd = tokens.get(fixspot).getStart();
			else
				charEnd = str.length() - 1;

			if( txt != null)
				txt.redrawRange(charStart, charEnd - charStart, false);
		}

		return allDefs;
	}
	
	private static HashSet<String> build(File in) {
		HashSet<String> result = new HashSet<String>();

		try {
			BufferedReader br = new BufferedReader(new FileReader(in));
			String line;

			while ((line = br.readLine()) != null) {
				line = line.trim().toLowerCase();

				if (line.length() != 0)
					result.add(line);
			}

			br.close();
		} catch (IOException e) {
		}

		return result;
	}
	
	private static String getFullString(Token cur, String fileData){
		if( !cur.inString() )
			return "";
		
		String type = "";
		Token fToken, lToken = null;
		
		if( cur.getAfter() == null)
			return "";
		
		fToken = cur.getAfter();
		
		
		while( (cur=cur.getAfter()) != null )
		{
			if(!cur.inString() || cur.getStr().equals("\""))
				break;
			
			lToken =cur;
		}
		
		type += fileData.substring(fToken.getStart(),Math.min(lToken == null ? fileData.length() -1 : lToken.getEnd(),fileData.length()-1));		
	
		return type;
	}
	
	private static boolean isNumber(String str){
		try{
			Integer.parseInt(str);
			return true;
		}
		catch(Exception e){
			return false;
		}
	}
	
	private void rebuildVars(String fileName, String data, ArrayList<Token> tokens) {
		ArrayList<Variable> newvars = new ArrayList<Variable>();
		ArrayList<Variable> oldvars = new ArrayList<Variable>();
			
		boolean changed = false, exists = false;
		
		int c = 0;
		while( c < lvars.size() ){
			if( lvars.get(c).getFilename().equals(fileName))
				oldvars.add(lvars.remove(c));
			else
				c++;
		}
		
		System.out.println("Parsing vars for " + fileName);

		for (Token tcur : tokens) {
			Variable newVar;

			if (tcur.getAfter() != null && tcur.inDefs() && tcur.getCDepth() == 0 && !tcur.inString() && !tcur.inDate() && tcur.getAfter().getStr().equals("=") && tcur.getAfter().getAfter() != null) {
				Token typeToken = tcur.getAfter().getAfter();
				boolean isConstant = true;
				
				if( typeToken == null )
					continue;
				
				String type = typeToken.getStr();

				//Strings
				if (typeToken.inString()){
					type = "\"" + getFullString(typeToken,data) + "\"";
				}
				
				//Date
				if( typeToken != null && typeToken.inDate() ){
					for(int i = 0; i<=5;i++)
					{		
						typeToken = typeToken.getAfter();
						
						if( typeToken == null)
							break;
						
						type += typeToken.getStr();
					}					
				}
				
				//Rate
				if( typeToken != null && isNumber(typeToken.getStr()) && typeToken.getAfter() != null && typeToken.getAfter().getStr().equals(".") && typeToken.getAfter().getAfter() != null && isNumber(typeToken.getAfter().getAfter().getStr())){
					for(int i = 0; i<=2;i++)
					{		
						typeToken = typeToken.getAfter();
						
						if( typeToken == null)
							break;
						
						type += typeToken.getStr();
					}	
				}
				
				//Money
				if( typeToken != null && typeToken.getStr().equals("$")){
					while( (typeToken = typeToken.getAfter()) != null )
					{
						if( !isNumber(typeToken.getStr()) && !typeToken.getStr().equals(",") && !typeToken.getStr().equals(".") )
							break;
									
						type += typeToken.getStr();
					}
				}
				
				for( DatabaseLayout.DataType cur : DatabaseLayout.DataType.values())
					if( cur.toString().equalsIgnoreCase(type))
						isConstant = false;
				
				//Array support
				if( typeToken != null && typeToken.getAfter() != null && typeToken.getAfter().getStr().equals("array")){

					while( (typeToken = typeToken.getAfter()) != null )
					{					
						if( !isNumber(typeToken.getStr()) && !typeToken.getStr().equals(")") && !typeToken.getStr().equals("(") && !typeToken.getStr().equals("array"))
							break;
									
						type += (isNumber(typeToken.getStr()) || typeToken.getStr().equals(")") ? "" : " ") + typeToken.getStr();
					}
				}
				
				newVar = new Variable(tcur.getStr(), fileName, tcur.getStart(), type);
				newVar.setConstant(isConstant);
				newvars.add(newVar);
			}
		}

		changed = !(oldvars.size() == newvars.size());
		
		if( !changed ){
			for (Variable var : newvars) {
				exists = false;
	
				for (Variable lvar : oldvars)
					if (lvar.equals(var))
						exists = true;
	
				if (!exists) {
					changed = true;
					break;
				}
			}
		}

		lvars.addAll(newvars);

		if (changed && fileName.equals(file.getName()))
			txt.redrawRange(0, txt.getText().length(), false);

	}
	
	public void textModified(int start, int length, String replacedText){
		if (reparse) {
			int st = start;
			int end = st + length;
			int oldend = st + replacedText.length();
			boolean rebuildVars = false;
	
			long time = System.currentTimeMillis();
	
			try {
				parse(file.getName(), txt.getText(), st, end, oldend, ltokens, lvars, txt);
				
				for( Token cur : lasttokens)
					if( cur.inDefs() )
						rebuildVars = true;
					
				if( rebuildVars )
					rebuildVars(file.getName(), txt.getText(), ltokens);
				
				if( refreshIncludes ){
					parseIncludes();
					refreshIncludes = false;
				}
			} catch (Exception e) {
				System.err.println("Syntax Highlighter error!");
				e.printStackTrace();
			}
			// parse(file.getName(),txt.getText(),txt.,end,end,ltokens,lvars);
			System.out.println("Parse time: " + (System.currentTimeMillis() - time));
		}
	}
	
	public synchronized void parseIncludes(){
		includes.clear();
		
		BackgroundIncludeParser parser = new BackgroundIncludeParser(txt.getText());
		parser.start();
	}
	
	public synchronized void errorCheck(){
		BackgroundSymitarErrorChecker checker = new BackgroundSymitarErrorChecker(this);
		checker.start();
	}

	public void reparseAll() {
		try {
			ltokens = new ArrayList<Token>();
			parse(file.getName(), txt.getText(), 0, txt.getCharCount() - 1, 0, ltokens, lvars, txt);
			rebuildVars(file.getName(), txt.getText(), ltokens);

			System.out.println("Reparsed");
		} catch (Exception e) {
			System.err.println("Syntax Highlighter error!");
			e.printStackTrace();
		}
	}


	public ArrayList<Token> getLtokens() {
		return ltokens;
	}

	public ArrayList<Variable> getLvars() {
		return lvars;
	}

	public void setReparse(boolean reparse) {
		this.reparse = reparse;
	}

	public boolean isReparse() {
		return reparse;
	}

	public SymitarFile getFile() {
		return file;
	}

	public void setFile(SymitarFile file) {
		this.file = file;
	}

	public int getSym() {
		return sym;
	}

	public void setSym(int sym) {
		this.sym = sym;
	}

	public StyledText getTxt() {
		return txt;
	}

	public void setTxt(StyledText txt) {
		this.txt = txt;
	}

	public static HashSet<String> getFunctions() {
		return functions;
	}

	public static void setFunctions(HashSet<String> functions) {
		RepgenParser.functions = functions;
	}

	public static HashSet<String> getKeywords() {
		return keywords;
	}

	public static void setKeywords(HashSet<String> keywords) {
		RepgenParser.keywords = keywords;
	}

	public static SpecialVariables getSpecialvars() {
		return specialvars;
	}

	public static void setSpecialvars(SpecialVariables specialvars) {
		RepgenParser.specialvars = specialvars;
	}

	public static DatabaseLayout getDb() {
		return db;
	}

	public static void setDb(DatabaseLayout db) {
		RepgenParser.db = db;
	}

	public void setErrorList(ArrayList<Error> errorList) {
		this.errorList = errorList;
	}

	public ArrayList<Error> getErrorList() {
		return errorList;
	}

	public void setTaskList(ArrayList<Task> taskList) {
		this.taskList = taskList;
	}

	public ArrayList<Task> getTaskList() {
		return taskList;
	}
}