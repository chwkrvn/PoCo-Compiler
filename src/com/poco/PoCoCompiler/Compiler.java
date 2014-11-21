//test
package com.poco.PoCoCompiler;

import com.poco.Extractor.Extractor;
import com.poco.Extractor.MethodSignaturesExtract;
import com.poco.Extractor.PointCutExtractor;
import com.poco.PoCoParser.PoCoLexer;
import com.poco.PoCoParser.PoCoParser;
import com.poco.Extractor.Closure;
import com.poco.StaticAnalysis.StaticAnalysis;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;


public class Compiler {
    /*
     * COMPILATION OPTIONS
     */
    /** Output verbose information to console */
    private final boolean verboseFlag;
    /** Quit compilation after a certain phase */
    private final String endAfterFlag;

    /*
     * FILES AND FOLDERS
     */
    /** Folder for compiler output */
    private Path outputDir;
    /** Path to main PoCo policy */
    private Path policyFilePath;
    /** Paths to files (jar or class) to be instrumented */
    private Path[] scanFilePaths;
    /** Used to write to the AspectJ file */
    private PrintWriter aspectWriter = null;
    /** Other policies that need to be parsed (found via "import" statements) */
    private LinkedHashSet<String> additionalPolicies = new LinkedHashSet<>();

    /*
     * COMPILATION RESULTS
     */
    /** Name of PoCo policy (e.g. CorysPolicy.poco is "CorysPolicy") */
    private String policyName;
    /** Parse tree generated by the ANTLR grammar */
    private ParseTree parseTree = null;
    /** Regular expressions from PoCo policy */
    private ArrayList<String> extractedREs = null;
    
     /** pointcut info  from PoCo policy */
    ArrayList<String> extractedPCs = new ArrayList<String>();
    LinkedHashSet<LinkedHashSet<String>> extractedPtCuts = null;
    
    /** All method signatures from files in scanFilePaths */
    private LinkedHashSet<String> extractedMethodSignatures = null;
    /** Each RE from the PoCo policy mapped to all matching methods */
    private LinkedHashMap<String, ArrayList<String>> regexMethodMappings = null; 
    private LinkedHashMap<String, ArrayList<String>> pointcutMappings = null;
    /** vars and marcos value will be saved in closure*/
    private Closure closure;

    /**
     * Writes a Collection object to a file, separated by newlines.
     * @param items object adhering to the Collection interface
     * @param savePath Path pointing to the save file location
     */
    public static void writeToFile(Collection<String> items, Path savePath) {
        try (FileWriter writeStream = new FileWriter(savePath.toFile())) {
            for (String item : items) {
                writeStream.write(item);
                writeStream.write('\n');
            }
        } catch (Exception ex) {
            System.out.println(ex.getMessage());
        }
    }

    public void addPolicy(String newPolicy) {
        additionalPolicies.add(newPolicy);
    }

    private void jOut(int indentLevel, String text, Object... args) {
        // Indent to appropriate level
        int numSpaces = indentLevel * 4;
        for (int i = 0; i < numSpaces; i++) {
            aspectWriter.format(" ");
        }

        // Output supplied format string and append newline
        aspectWriter.format(text, args);
        aspectWriter.format("\n");
    }

    /**
     * Writes a hash map to a file. Each item is separated by a newline. Keys are left-justified and their
     * values are indented 4 spaces.
     * @param map LinkedHashMap to write to file (it's much faster to iterate over LinkedHashMaps)
     * @param savePath Path pointing to the save file location
     */
    public static void writeMapToFile(LinkedHashMap<String, ArrayList<String>> map, Path savePath) {
        try (FileWriter writeStream = new FileWriter(savePath.toFile())) {
            for (String key : map.keySet()) {
                writeStream.write(key + ":\n");
                for (String value : map.get(key)) {
                    writeStream.write("    ");
                    writeStream.write(value);
                    writeStream.write('\n');
                }
                writeStream.write('\n');
            }
        } catch (Exception ex) {
            System.out.println(ex.getMessage());
        }
    }

    /**
     * Outputs log information to console if the verbose flag has been set (-v command line option).
     * @param format printf-style format string
     * @param args arguments to printf
     */
    private void vOut(String format, Object... args) {
        if (verboseFlag) {
            System.out.printf(format, args);
        }
    }

    /**
     * Constructor. Parses command-line arguments and outputs execution information.
     * @param arguments command-line arguments
     */
    public Compiler(String[] arguments) {
        // Set up command-line option parser (see JOpts library documentation for more information)
        OptionParser optParser = new OptionParser();
        optParser.accepts("extract");
        OptionSpec<String> outputOpt = optParser.accepts("o").withRequiredArg().ofType( String.class ).defaultsTo(Paths.get("").toAbsolutePath().toString());
        OptionSpec<String> scanOpts = optParser.accepts("c").withRequiredArg().ofType( String.class );
        OptionSpec<String> policyArgs = optParser.nonOptions().ofType( String.class );
        optParser.accepts("v");
        OptionSet options = optParser.parse(arguments);

        // User wants verbose output?
        this.verboseFlag = options.has("v");

        // Configure output directory
        this.outputDir = Paths.get(outputOpt.value(options));

        // Get name for PoCo Policy
        if (policyArgs.value(options) == null) {
            System.out.println("ERROR: Please provide at least one PoCo policy file.");
            System.exit(-1);
        }

        // Set up path to policy file and get name
        this.policyFilePath = Paths.get(policyArgs.value(options));
        String policyFileName = policyFilePath .getFileName().toString();
        this.policyName = policyFileName.substring(0, policyFileName.indexOf('.'));

        // Set up list of files to instrument/scan
        this.scanFilePaths = new Path[scanOpts.values(options).size()];
        for (int i = 0; i < scanOpts.values(options).size(); i++) {
            scanFilePaths[i] = Paths.get(scanOpts.values(options).get(i));
        }

        // "--extract" option indicates that the user only wants to extract REs
        if (options.has("extract")) {
            this.endAfterFlag = "extract";
        } else {
            this.endAfterFlag = "";
        }

        // Output execution information
        vOut("PoCo Compiler starting up with the following options:\n");
        if (endAfterFlag.length() > 0) {
            vOut("%s\n  %s\n", "End After:", endAfterFlag);
        }
        vOut("%s\n  %s\n", "PoCo Policy:", policyFilePath.toString());
        vOut("%s\n  %s\n", "Output Dir:", outputDir.toString());
        vOut("%s\n", "Scan Targets:");
        for (Path scanFilePath : scanFilePaths) {
            vOut("  %s\n", scanFilePath.toString());
        }
        if (scanFilePaths.length == 0) {
            vOut("  %s\n", "(None)");
        }
        vOut("\n");
    }

    /**
     * Public-facing method to execute the compilation phases in the correct order.
     */
    public void compile() {
        // Runs through the steps of compilation (parse, extract, mapping)
        this.doParse();
        this.doGenerateClosure();
        this.doExtract();
        this.doStaticAnalysis();

        // User wants to only do extracts
        if (endAfterFlag.equals("extract")) {
            return;
        }

        this.doMapping();
        this.doGenerateAspectJ();
    }

    /**
     * Parses the supplied PoCo Policy file, if it exists. Otherwise exits with error.
     *
     * Step #1 in compilation process as the parse tree is required by other phases.
     */
    private void doParse() {
        // Parse the specified PoCo policy
        vOut("Parsing PoCo Policy...\n");
        ANTLRInputStream antlrStream = null;

        // Open the PoCo policy file
        try {
            antlrStream = new ANTLRInputStream(new FileInputStream(policyFilePath.toFile()));
        } catch (Exception ex) {
            System.out.println(ex.getMessage());
            ex.printStackTrace();
            System.exit(-1);
        }

        // Call lexer, get tokens, pass tokens to parser. Obtain the parseTree for the root-level rule, "policy".
        PoCoLexer lexer = new PoCoLexer(antlrStream);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        PoCoParser parser = new PoCoParser(tokens);
        this.parseTree = parser.policy();
    }

/**
     * Extracts vars and macros that defined before executions,
     * so that the Extractor will be able to get info for generating pointcut
    */
    private void doGenerateClosure() {
        ExtractClosure extractClosure = new ExtractClosure(this.closure);
        extractClosure.visit(parseTree);
        closure =  extractClosure.getClosure();
    }

    /**
     * Extracts the REs from the PoCo policy for use by the mapping functions. Also extracts all method signatures from
     * all to-be-instrumented files.
     *
     * Step #2 in compilation process. Requires doParse() to have been called prior.
     */
    private void doExtract() {
        // Extract REs from PoCo Policy for mapping
        vOut("Extracting REs from policy...\n");
        Extractor regexExtractor = new Extractor();
        regexExtractor.visit(parseTree);
        this.extractedREs = regexExtractor.getMatchStrings();
        PointCutExtractor pcExtractor = new PointCutExtractor(this.closure);
        pcExtractor.visit(parseTree);
        this.extractedPtCuts = pcExtractor.getgetPCStrings();

        for(LinkedHashSet<String> entry: this.extractedPtCuts) {
            extractedPCs.addAll(entry);
        }
        // Write REs to a file
        Path policyExtractPath = outputDir.resolve(policyName + "_extracts.txt");
        //writeToFile(extractedREs, policyExtractPath);
        writeToFile(extractedPCs, policyExtractPath);
        // Extract all method signatures from jar/class files
        vOut("Extracting method signatures from scan files...\n");
        this.extractedMethodSignatures = new LinkedHashSet<>();
        for (Path scanFilePath : scanFilePaths) {
            this.extractedMethodSignatures.addAll(new MethodSignaturesExtract(scanFilePath).getMethodSignatures());
        }

        // Write the extracted methods to a file
        Path methodExtractPath = outputDir.resolve(policyName + "_allmethods.txt");
        writeToFile(extractedMethodSignatures, methodExtractPath);
    }

    /**
     * Runs static analysis on policy. doExtract() must have already been called
     */
    private void doStaticAnalysis()
    {
        vOut("Performing static analysis...\n");
        ANTLRInputStream antlrStream = null;

        try {
            antlrStream = new ANTLRInputStream(new FileInputStream(policyFilePath.toFile()));
            StaticAnalysis sa = new StaticAnalysis();
            sa.StaticAnalysis(antlrStream, this.extractedMethodSignatures);
        } catch (Exception ex) {
            System.out.println(ex.getMessage());
            ex.printStackTrace();
            System.exit(-1);
        }
    }

    /**
     * Maps the REs from the PoCo policy to the extracted method signatures.
     *
     * Step #3 in the compilation process. doParse() and doExtract() should be called prior.
     */
    private void doMapping() {
        // Generate mappings from extracted REs -> method signatures
        vOut("Mapping REs from policy to method signatures...\n");
        //RegexMapper mapper = new RegexMapper(extractedREs, extractedMethodSignatures);
        RegexMapper mapper = new RegexMapper(extractedPCs, extractedMethodSignatures);
        mapper.mapRegexes();
        this.regexMethodMappings = mapper.getMappings();

        // Write mappings to a text file
        Path mappingExtractPath = outputDir.resolve(policyName + "_mappings.txt");
        writeMapToFile(regexMethodMappings, mappingExtractPath);
    }

    /**
     *
     * Step #4 in the compilation process.
     */
    private void doGenerateAspectJ() {
        // Generate AspectJ pointcuts according to the mappings
        Path poincutPath = outputDir.resolve("Aspect" + policyName + ".aj");
        String aspectName = "Aspect" + policyName;
        vOut("Generating AspectJ file %s ...\n", poincutPath.getFileName());


        // Open up the AspectJ file for writing
        try {
            aspectWriter = new PrintWriter(poincutPath.toFile());
        } catch (Exception ex) {
            System.out.println("ERROR during pointcut gen");
            System.out.println(ex.getMessage());
            System.exit(-1);
        }

        // Create some class names
        String childPolicyName = policyName;

        outAspectPrologue(aspectName, childPolicyName);

        int pointcutNum = 0;
        //for (Map.Entry<String, ArrayList<String>> entry : this.regexMethodMappings.entrySet()) {
        for(LinkedHashSet<String> entry: this.extractedPtCuts) {
            jOut(1, "pointcut PointCut%d():", pointcutNum);

                /*
                Generating Pointcuts:
                    - RE doesn't care about arguments (like `method(%)')
                        * Replace argument list with '..' and place each signature in a set to remove duplicates
                    - RE does care about arguments (like `method(#String{Hello})')
                        * Parse out object name from PoCo object syntax
                 */

            /*LinkedHashSet<String> signatures = new LinkedHashSet<>();
            for (String signature : entry.getValue()) {
                signatures.add(signature.substring(0, signature.indexOf('(')) + "(..)");
            }*/

            int signatureCount = 0;
            for (String signature : entry) {
                signatureCount++;
                if (signatureCount < entry.size()) {
                    jOut(2, "execution(%s) ||", signature);
                } else {
                    jOut(2, "execution(%s);\n", signature);
                }
            }


            pointcutNum++;
        }

        // Generate advice
        pointcutNum = 0;
        for(LinkedHashSet<String> entry: this.extractedPtCuts) {
        //for (Map.Entry<String, ArrayList<String>> entry : this.regexMethodMappings.entrySet()) {
            outAdvicePrologue("PointCut" + pointcutNum);
            jOut(2, "root.queryAction(new Event(thisJoinPoint));");
            outAdviceEpilogue();

            pointcutNum++;
        }

        // Generate policy classes
        PolicyVisitor pvisitor = new PolicyVisitor(aspectWriter, 1, this.closure);
        pvisitor.visit(parseTree);

        outAspectEpilogue();

        aspectWriter.close();
        aspectWriter = null;
    }

    private void outAspectPrologue(String aspectName, String childName) {
        jOut(0, "import com.poco.PoCoRuntime.*;\n");
        jOut(0, "public aspect %s {", aspectName);
        jOut(1, "private DummyRootPolicy root = new DummyRootPolicy( new %s() );\n", childName);
    }

    private void outAspectEpilogue() {
        jOut(0, "}");
    }

    private void outAdvicePrologue(String pointcutName) {
        jOut(1, "Object around(): %s() {", pointcutName);
    }

    private void outAdviceEpilogue() {
        jOut(2, "return proceed();");
        jOut(1, "}\n");
    }

    public static void main(String[] args) {
        Compiler compiler = new Compiler(args);
        compiler.compile();
    }
}
