package fr.cea.ig.grools.reporter;
/*
 * Copyright LABGeM 19/02/15
 *
 * author: Jonathan MERCIER
 *
 * This software is a computer program whose purpose is to annotate a complete genome.
 *
 * This software is governed by the CeCILL  license under French law and
 * abiding by the rules of distribution of free software.  You can  use,
 * modify and/ or redistribute the software under the terms of the CeCILL
 * license as circulated by CEA, CNRS and INRIA at the following URL
 * "http://www.cecill.info".
 *
 * As a counterpart to the access to the source code and  rights to copy,
 * modify and redistribute granted by the license, users are provided only
 * with a limited warranty  and the software's author,  the holder of the
 * economic rights,  and the successive licensors  have only  limited
 * liability.
 *
 * In this respect, the user's attention is drawn to the risks associated
 * with loading,  using,  modifying and/or developing or reproducing the
 * software by the user in light of its specific status of free software,
 * that may mean  that it is complicated to manipulate,  and  that  also
 * therefore means  that it is reserved for developers  and  experienced
 * professionals having in-depth computer knowledge. Users are therefore
 * encouraged to load and test the software's suitability as regards their
 * requirements in conditions enabling the security of their systems and/or
 * data to be ensured and,  more generally, to use and operate it in the
 * same conditions as regards security.
 *
 * The fact that you are presently reading this means that you have had
 * knowledge of the CeCILL license and that you accept its terms.
 */

import ch.qos.logback.classic.Logger;
import fr.cea.ig.grools.common.Command;
import fr.cea.ig.grools.common.ResourceExporter;
import fr.cea.ig.grools.common.WrapFile;
import fr.cea.ig.grools.fact.Concept;
import fr.cea.ig.grools.fact.Observation;
import fr.cea.ig.grools.fact.PriorKnowledge;
import fr.cea.ig.grools.fact.Relation;
import fr.cea.ig.grools.logic.Conclusion;
import fr.cea.ig.grools.logic.TruthValue;
import fr.cea.ig.grools.logic.TruthValuePowerSet;
import fr.cea.ig.grools.reasoner.Mode;
import fr.cea.ig.grools.reasoner.Reasoner;
import fr.cea.ig.grools.reasoner.VariantMode;
import lombok.NonNull;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 *
 */
/*
 * @startuml
 * class Reporter{
 *  - outputDir   : String
 *  - tableReport : TableReport
 *  - csvReport   : CSVReport
 * }
 * @enduml
 */
public final class Reporter {
    private static transient final Logger                                                                   LOGGER               = ( Logger ) LoggerFactory.getLogger( Reporter.class );
    private static final           EnumMap<SensitivitySpecificity,List<PriorKnowledge>>                     pathwaysStats        = new EnumMap<>( SensitivitySpecificity.class );
    private static final           Map<PriorKnowledge,EnumMap<SensitivitySpecificity,List<PriorKnowledge>>> functionalUnitsStats = new HashMap<>(  );
    private final String        outputDir;
    private final TableReport   tableReport;
    private final CSVReport     csvReport;
    private final CSVSensitivitySpecificity csvPathwaysStats;
    private final CSVSensitivitySpecificity csvFunctionalUnityStats;

    @NonNull
    private static EnumMap<SensitivitySpecificity,Integer> classifyConclusions( @NonNull final Map<Conclusion,Integer> conclusions ){
        final EnumMap<SensitivitySpecificity,Integer> stats = new EnumMap<>( SensitivitySpecificity.class );
        stats.put( SensitivitySpecificity.TRUE_POSITIVE , conclusions.getOrDefault( Conclusion.CONFIRMED_PRESENCE, 0 ) );
        stats.put( SensitivitySpecificity.TRUE_NEGATIVE , conclusions.getOrDefault( Conclusion.CONFIRMED_ABSENCE, 0 )
                                                        + conclusions.getOrDefault( Conclusion.ABSENT, 0 ));
        stats.put( SensitivitySpecificity.FALSE_POSITIVE, conclusions.getOrDefault( Conclusion.UNEXPECTED_PRESENCE, 0 )
                                                        + conclusions.getOrDefault( Conclusion.CONTRADICTORY_PRESENCE, 0 ) );
        stats.put( SensitivitySpecificity.FALSE_NEGATIVE, conclusions.getOrDefault( Conclusion.MISSING, 0 )
                                                        + conclusions.getOrDefault( Conclusion.UNEXPECTED_ABSENCE, 0 )
                                                        + conclusions.getOrDefault( Conclusion.CONTRADICTORY_ABSENCE, 0 ) );
        return stats;
    }

    public static SensitivitySpecificity toClassification( @NonNull final Conclusion conclusion ){
        SensitivitySpecificity result = null;
        switch ( conclusion ){
            case CONFIRMED_PRESENCE: result = SensitivitySpecificity.TRUE_POSITIVE; break;
            case CONFIRMED_ABSENCE: result = SensitivitySpecificity.TRUE_NEGATIVE; break;
            case UNEXPECTED_PRESENCE:
            case ABSENT: result = SensitivitySpecificity.FALSE_POSITIVE; break;
            case MISSING:
            case UNEXPECTED_ABSENCE:
            case CONTRADICTORY_ABSENCE: result = SensitivitySpecificity.FALSE_NEGATIVE; break;
            default: result = SensitivitySpecificity.NOT_AVAILABLE;
        }
        return result;
    }
    
    private static String colorList( final PriorKnowledge pk ) {
        return  toApproxExp( pk.getExpectation( ) )[1] + ";0.5:" + toApproxPred( pk.getPrediction( ) )[1];
    }
    
    private static String[] toApproxPred( final TruthValuePowerSet tvSet ) {
        final String[] result = new String[2];
        switch( tvSet ) {
            case T:
                result[0] = "True";
                result[1] = "Lime";
                break;
            case F:
                result[0] = "False";
                result[1] = "Coral";
                break;
            case NB:
            case NTB:
            case TB:
            case TF:
            case TFB:
            case NTF:
            case NTFB:
            case FB:
            case NFB:
            case B:
                result[0] = "Both";
                result[1] = "Plum";
                break;
            case n:
            case NT:
            case NF:
            case N:
            default:
                result[0] = "Unknown";
                result[1] = "white";
        }
        return result;
    }
    
    private static String[] toApproxExp( final TruthValuePowerSet tvSet ) {
        String[] result = new String[2];
        switch( tvSet ) {
            case NT:
            case T:
                result[0] = "True";
                result[1] = "Lime";
                break;
            case NF:
            case F:
                result[0] = "False";
                result[1] = "Coral";
                break;
            case NB:
            case NTB:
            case TB:
            case TF:
            case TFB:
            case NTF:
            case NTFB:
            case FB:
            case NFB:
            case B:
                result[0] = "Both";
                result[1] = "Plum";
                break;
            case n:
            case N:
            default:
                result[0] = "Unknown";
                result[1] = "white";
        }
        return result;
    }
    
    private static String priorKnowledgeToHTML( @NonNull final PriorKnowledge pk ) {
        return String.format( "<b>Description:</b> %s<br><b>is specific:</b> %s<br><b>is dispensable:</b> %s<br><b>Expectation:</b> %s - %s<br><b>Prediction:</b> %s - %s<br><b>Conclusion:</b> %s",
                              pk.getDescription( ).replaceAll( "\'", "&quote;" )    ,
                              pk.getIsSpecific( ) ? "Yes" : "No"                    ,
                              pk.getIsDispensable( ) ? "Yes" : "No"                 ,
                              toApproxExp(pk.getExpectation( ))[0]                  ,
                              pk.getExpectation( )                                  ,
                              toApproxPred( pk.getPrediction( ) )[0]                ,
                              pk.getPrediction( )                                   ,
                              pk.getConclusion( ) );
    }
    
    private static String observationToHTML( @NonNull final Observation observation ) {
        return String.format( "%s<br><b>Description:</b> %s<br><b>type:</b> %s<br><b>Truth value:</b> %s", observation.getLabel( ), observation.getDescription( ).replaceAll( "\'", "&quote;" ), observation.getType( ), observation.getTruthValue( ) );
    }
    
    private static boolean addNode( @NonNull final Concept concept, @NonNull final String id, @NonNull final DotFile dotFile ) {
        boolean status = false;
        final DotAttribute.DotAttributeBuilder attribute = new DotAttribute.DotAttributeBuilder();
        attribute.id( id )
                 .label( concept.getName() );
        if( concept instanceof PriorKnowledge ) {
            final PriorKnowledge pk = ( PriorKnowledge ) concept;
            attribute.fillcolor( colorList( pk ) )
                     .shape( "box" )
                     .style( "filled" )
                     .style( "rounded" )
                     .color( "black" );
            if( pk.getIsDispensable() )
                attribute.style( "dashed" );
            status = true;
        }
        else if( concept instanceof Observation ) {
            final Observation o = ( Observation ) concept;
            final String fillcolor = ( o.getTruthValue( ) == TruthValue.t ) ? "Lime" : "Coral";
            attribute.fillcolor( fillcolor )
                     .shape( "oval" )
                     .style( "filled" );
            status = true;
        }
        dotFile.addNode( attribute.build()  );
        return status;
    }
    
    private static String underscoretify( @NonNull final String str ) {
        return str.replaceAll( "[\\s.+\\-,.:/!$()\\[\\]]", "_" )
                  .replaceAll( "(_)(\\1{2,})", "$1" );
    }
    
    private static void writeJSInfo( @NonNull final JsFile jsFile, @NonNull final Concept concept, @NonNull final String graphName ) throws IOException {
        String       color;
        final String name = underscoretify( concept.getName( ) );
        if( concept instanceof PriorKnowledge ) {
            final PriorKnowledge priorKnowledge = ( PriorKnowledge ) concept;
            switch( priorKnowledge.getConclusion( ) ) {
                case CONFIRMED_ABSENCE:
                case CONFIRMED_PRESENCE:
                    color = "Chartreuse";
                    break;
                case UNCONFIRMED_PRESENCE:
                case UNCONFIRMED_ABSENCE:
                    color = "White";
                    break;
                default:
                    color = "LightPink";
                    break;
            }
            jsFile.writeln( String.format( "    const svg_%s = svgdoc_%s.getElementById('%s');", name, graphName, name ) );
            jsFile.writeln( String.format( "    const svg_%s_path = getPathParentToChild( '%s', nodes_%s, edges_%s, [] );", name, name, graphName, graphName ) );
            jsFile.writeln( String.format( "    tooltips_event( svg_%s, '%s', '%s', '%s', graph_%s, svg_%s_path );", name, priorKnowledge.getName() , priorKnowledgeToHTML( priorKnowledge ), color, graphName, name ) );
        }
        else if( concept instanceof Observation ) {
            final Observation observation = ( Observation ) concept;
            color = "White";
            jsFile.writeln( String.format( "    const svg_%s = svgdoc_%s.getElementById('%s');", name, graphName, name ) );
            jsFile.writeln( String.format( "    const svg_%s_path = getPathChildToParent( '%s', nodes_%s, edges_%s, [] );", name, name, graphName, graphName ) );
            jsFile.writeln( String.format( "    tooltips_event( svg_%s, '%s', '%s', '%s', graph_%s, svg_%s_path );", name, observation.getName() , observationToHTML( observation ), color, graphName, name ) );
        }
    }
    
    private void dotToSvg( @NonNull final String graphName, @NonNull final DotFile dotFile ) throws Exception {
        final String outFile = Paths.get( outputDir, graphName, graphName + ".svg" ).toString( );
        Command.run( "dot", Arrays.asList( "-Tsvg", "-o" + outFile, dotFile.getAbsolutePath( ) ) );
    }
    
    private String writeDotFile( @NonNull final String graphName, @NonNull final Set<Relation> relations, final Set<Concept> concepts ) throws Exception {
        final String    dotFilename = Paths.get( outputDir, graphName, graphName + ".dot" ).toString( );
        final DotFile   dotFile     = new DotFile( graphName, dotFilename );
        final Mode      mode        = SharedData.getInstance()
                                                .getReasoner()
                                                .getMode();
        for( final Concept concept : concepts ) {
            final String sourceId = underscoretify( concept.getName( ) );
            if( !addNode( concept, sourceId, dotFile ) ) {
                LOGGER.warn( "Unexpected type: " + concept.getClass( ) );
            }
        }
        
        for( final Relation relation : relations ) {
            final Concept source   = relation.getSource( );
            final Concept target   = relation.getTarget( );
            final String  sourceId = underscoretify( source.getName( ) );
            final String  targetId = underscoretify( target.getName( ) );
            final DotAttribute.DotAttributeBuilder attribute = new DotAttribute.DotAttributeBuilder();
            attribute.label( relation.getType( ).toString( ) );
            if( mode.getVariants().contains( VariantMode.SPECIFIC ) && source instanceof PriorKnowledge && ((PriorKnowledge)source).getIsSpecific( ) )
                attribute.color( "black" );
            dotFile.linkNode( sourceId, targetId, attribute.build() );
        }
        
        dotFile.close( );
        dotToSvg( graphName, dotFile );
        return dotFilename;
    }
    
    private String writeJsFile( final String graphName, final Set<Concept> concepts ) throws IOException {
        final String jsFilename = Paths.get( outputDir, graphName, "js", graphName + ".js" ).toString( );
        final JsFile jsFile     = new JsFile( jsFilename );
        jsFile.writeln( String.format( "    const object_svg_%s   = document.getElementById('%s');", graphName, graphName ) );
        jsFile.writeln( String.format( "    const svgdoc_%s       = object_svg_%s.contentDocument;", graphName, graphName ) );
        jsFile.writeln( String.format( "    const graph_%s        = Array.from( svgdoc_%s.querySelectorAll( \"g.node,g.edge\" ) );", graphName, graphName ) );
        jsFile.writeln( String.format( "    const nodes_%s        = Array.from( svgdoc_%s.querySelectorAll( \"g.node\" ) );", graphName, graphName ) );
        jsFile.writeln( String.format( "    const edges_%s        = Array.from( svgdoc_%s.querySelectorAll( \"g.edge\" ) );", graphName, graphName ) );
        for( final Concept concept : concepts )
            writeJSInfo( jsFile, concept, graphName );
        jsFile.close( );
        return jsFilename;
    }
    
    private String writeTableReport( @NonNull final Path path, @NonNull final Set< Concept > concepts, @NonNull final EnumMap< SensitivitySpecificity, List< PriorKnowledge > > functionalUnitsStats ) throws Exception {
        final TableReport table = new TableReport( path.toFile( ), "../" );
        for( final Concept concept : concepts ) {
            if( concept instanceof PriorKnowledge )
                table.addRow( ( PriorKnowledge ) concept );
        }
        table.closeTable();
        table.addStats( "Functional Units", "stats", functionalUnitsStats );
        table.close( );
        return table.getFileName( );
    }
    
    private String writeCSVReport( @NonNull final Path path, @NonNull final Set<Concept> concepts ) throws Exception {
        final CSVReport csv = new CSVReport( path.toFile( ) );
        for( final Concept concept : concepts )
            csv.addRow( concept );
        csv.close( );
        return csv.getFileName( );
    }

    private String writeCSVFunctionalUnitsClass( @NonNull final Path path, @NonNull final Map<SensitivitySpecificity,List<PriorKnowledge>> values ) throws Exception{
        final CSVSensitivitySpecificity csv = new CSVSensitivitySpecificity( path.toFile() );
        for( final Map.Entry<SensitivitySpecificity, List< PriorKnowledge>> entry : values.entrySet( ) ){
            for( final PriorKnowledge pk : entry.getValue() )
                csv.addRow( pk );
        }
        csv.close( );
        return csv.getFileName( );
    }

    private String writeJSONFile( @NonNull final Path jsonPathFile, @NonNull final Set<Relation> relations, final Set<Concept> concepts ) throws Exception {
        final WrapFile      json          = new WrapFile( jsonPathFile.toFile( ) );
        final AtomicInteger atomicInteger = new AtomicInteger( 0 );
        json.writeln( "{" );
        json.writeln( "  \"edges\": [" );
        final String edges = relations.stream( )
                                      .map( rel -> String.format( "    {\"source\": \"%s\", \"target\":\"%s\" ,\"id\":\"%s_%s\" , \"type\": \"arrow\", \"label\":\"%s\"}", rel.getSource( ).getName( ), rel.getTarget( ).getName( ), rel.getSource( ).getName( ), rel.getTarget( ).getName( ), rel.getType( ) ) )
                                      .collect( Collectors.joining( ",\n" ) );
        json.writeln( edges );
        json.writeln( "  ]" );
        json.writeln( "  \"nodes\": [" );
        final String nodes = concepts.stream( )
                                     .map( concept -> String.format( "     {\"label\":\"%s\",\"id\":\"%s\" ,\"color\":\"rgb(0,255,0)\",\"size\":1, \"x\": %.2f, \"y\": %.2f}", concept.getLabel( ), concept.getName( ), Math.cos( Math.PI * 2 * atomicInteger.get( ) / concepts.size( ) ), Math.sin( Math.PI * 2 * atomicInteger.getAndIncrement( ) / concepts.size( ) ) ) )
                                     .collect( Collectors.joining( ",\n" ) );
        json.writeln( nodes );
        json.writeln( "  ]" );
        json.writeln( "}" );
        json.close( );
        return jsonPathFile.toString( );
    }

    private static FunctionalUnitStats subGraphStat( @NonNull final Set< Relation > relations, @NonNull final Set< PriorKnowledge > priorKnowledges, @NonNull final CSVSensitivitySpecificity csvFunctionalUnityStats )throws IOException{
        final Map<String,Float> stats = new TreeMap<>(  );

        final Set<PriorKnowledge> sources = relations.stream( )
                                              .filter( relation -> relation.getSource() instanceof PriorKnowledge )
                                              .filter( relation -> relation.getTarget() instanceof PriorKnowledge )
                                              .map(    relation -> ( PriorKnowledge ) relation.getSource() )
                                              .collect( Collectors.toSet( ) );


        stats.put( "nb concepts", ( float ) priorKnowledges.size( ) );

        final Set<PriorKnowledge> targets = relations.stream( )
                                                     .filter( relation -> relation.getSource() instanceof PriorKnowledge )
                                                     .filter( relation -> relation.getTarget() instanceof PriorKnowledge )
                                                     .map(    relation -> ( PriorKnowledge ) relation.getTarget() )
                                                     .collect( Collectors.toSet( ) );

        final Set<PriorKnowledge> leaves = priorKnowledges.stream()
                                                          .filter( pk -> sources.contains( pk ) )
                                                          .filter( pk -> !targets.contains( pk ) )
                                                          .peek( pk -> {
                                                              try {
                                                                  csvFunctionalUnityStats.addRow( pk );
                                                              }
                                                              catch(IOException e) {
                                                                  throw new UncheckedIOException( e );
                                                              }
                                                          } )
                                                          .collect( Collectors.toSet( ) );


        stats.put( "nb leaf concepts", ( float ) leaves.size( ) );
        final Map<Conclusion, Long> conclusionsStats = leaves.stream( )
                                                             .map( leaf -> leaf.getConclusion() )
                                                             .collect(Collectors.groupingBy( Function.identity( ), Collectors.counting( ) ) );
        conclusionsStats.forEach( (k,v) -> stats.put( k.toString(), v.floatValue() ) );

        final EnumMap<SensitivitySpecificity,List<PriorKnowledge>> leavesClassified = leaves.stream()
                                                                                            .collect( Collectors.groupingBy( pk -> toClassification( pk.getConclusion() ), () -> new EnumMap<>(SensitivitySpecificity.class), Collectors.toList() ) );

        return new FunctionalUnitStats(stats, leavesClassified);
    }

    public Reporter( @NonNull final String outDir, @NonNull final Reasoner reasoner ) throws Exception {
        outputDir               = outDir;
        tableReport             = new TableReport( true, Paths.get( outputDir, "index.html" ).toFile( ), "./" );
        csvReport               = new CSVReport( Paths.get( outputDir, "results.csv" ).toFile( ) );
        csvPathwaysStats        = new CSVSensitivitySpecificity( Paths.get( outputDir, "pathways_stats.csv" ).toFile( ) );
        csvFunctionalUnityStats = new CSVSensitivitySpecificity( Paths.get( outputDir, "functional_units_stats.csv" ).toFile( ) );

        // Initialize map
        pathwaysStats.clear();
        functionalUnitsStats.clear();
        
        SharedData instance = SharedData.getInstance();
        instance.setReasoner( reasoner );

        String jsPath1 = ResourceExporter.export( "/js/svg_common.js"   , outputDir );
        String jsPath2 = ResourceExporter.export( "/js/list.js"         , outputDir );
        String imgClose= ResourceExporter.export( "/img/close.png"      , outputDir );
        String cssPath1= ResourceExporter.export( "/css/grools.css"     , outputDir );
        String cssPath2= ResourceExporter.export( "/css/table.css"      , outputDir );
        LOGGER.debug( "File copied " + jsPath1 );
        LOGGER.debug( "File copied " + jsPath2 );
        LOGGER.debug( "File copied " + imgClose );
        LOGGER.debug( "File copied " + cssPath1 );
        LOGGER.debug( "File copied " + cssPath2 );
    }
    
    public void addGraph( @NonNull final PriorKnowledge priorKnowledge, @NonNull Set<Relation> relations ) throws Exception {
        final String graphName = priorKnowledge.getName( ).replace( "-", "_" );
        
        final File outDir = Paths.get( outputDir, graphName ).toFile( );
        outDir.mkdirs( );
        final GraphicReport graphicReport = new GraphicReport( Paths.get( outputDir, graphName, "result_svg.html" ).toString( ) );
        
        final Set<Concept> concepts = relations.stream( )
                                               .map( rel -> Arrays.asList( rel.getSource( ), rel.getTarget( ) ) )
                                               .flatMap( Collection::stream )
                                               .collect( Collectors.toSet( ) );

        final Set<PriorKnowledge> priorKnowledges = concepts.stream()
                                                            .filter(    c -> c instanceof PriorKnowledge )
                                                            .map(       c -> (PriorKnowledge)c )
                                                            .collect( Collectors.toSet() );
        final FunctionalUnitStats stats = subGraphStat( relations, priorKnowledges, csvFunctionalUnityStats );


        functionalUnitsStats.put( priorKnowledge, stats.getLeavesClass().clone() );
        final SensitivitySpecificity category = toClassification( priorKnowledge.getConclusion() );
        final List<PriorKnowledge> pathways = pathwaysStats.getOrDefault( category , new ArrayList<>(  ) );
        pathways.add( priorKnowledge );
        pathwaysStats.put( category, pathways );
        csvPathwaysStats.addRow( priorKnowledge );
        
        final String dotFilename    = writeDotFile( graphName, relations, concepts );
        final String jsFilename     = writeJsFile( graphName, concepts );
        final String trFilename     = writeTableReport( Paths.get( outputDir, graphName, "result_table.html" ), concepts, stats.getLeavesClass() );
        final String csvFilename    = writeCSVReport( Paths.get( outputDir, graphName, "results.csv" ), concepts );
        final String jsonFilename   = writeJSONFile( Paths.get( outputDir, graphName, "results.json" ), relations, concepts );
        final String csvFUCFilename = writeCSVFunctionalUnitsClass( Paths.get( outputDir, graphName, "functional_units_stats.csv" ), stats.getLeavesClass() );
        graphicReport.addGraph( graphName, graphName + ".svg" );
        graphicReport.close( );

        final String url         = graphName + "<br>"
                                             + " <a href=\"" + Paths.get( graphName, "result_svg.html" ).toString( )   + "\">SVG</a>"
                                             + " <a href=\"" + Paths.get( graphName, "result_table.html" ).toString( ) + "\">Table</a>";
        final int    tildeIndex  = priorKnowledge.getDescription( ).indexOf( '~' );
        final String description = ( tildeIndex >= 0 ) ? priorKnowledge.getDescription( ).substring( 0, tildeIndex )
                                                       : priorKnowledge.getDescription( );
        tableReport.addRow( priorKnowledge, stats.getConclusionsStats(), url, description );
        csvReport.addRow( priorKnowledge );
        LOGGER.debug( "File copied " + jsFilename );
        LOGGER.debug( "File copied " + trFilename );
        LOGGER.debug( "File copied " + jsonFilename );
        LOGGER.debug( "File copied " + csvFilename );
        LOGGER.debug( "File copied " + dotFilename );
        LOGGER.debug( "File copied " + csvFUCFilename );
    }
    
    public void close( ) throws IOException {
        tableReport.closeTable();
        tableReport.addStats( "Pathways", "pathways_stats", pathwaysStats, functionalUnitsStats );
        tableReport.addStats( "Functional Units", "functional_units_stats", functionalUnitsStats );
        tableReport.close( );
        csvReport.close( );
        csvPathwaysStats.close( );
        csvFunctionalUnityStats.close();
    }
    
    public void finalize( ) throws Throwable {
        if( !tableReport.isClosed( ) )
            tableReport.close( );
        if( !csvReport.isClosed( ) )
            csvReport.close( );
        if( !csvPathwaysStats.isClosed( ) )
            csvPathwaysStats.close( );
        if( !csvFunctionalUnityStats.isClosed( ) )
            csvFunctionalUnityStats.close( );
    }
    
    
}
