package focusedCrawler.memex.cdr;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import focusedCrawler.target.model.TargetModelJson;
import focusedCrawler.target.repository.FileSystemTargetRepository;
import focusedCrawler.target.repository.FileSystemTargetRepository.DataFormat;
import focusedCrawler.target.repository.FilesTargetRepository;
import focusedCrawler.tools.SimpleBulkIndexer;
import focusedCrawler.util.CliTool;
import focusedCrawler.util.persistence.PersistentHashtable;
import io.airlift.airline.Command;
import io.airlift.airline.Option;

@Command(name="AcheToCdrExporter", description="Exports crawled data to CDR format")
public class AcheToCdrExporter extends CliTool {
    
    private static final ObjectMapper jsonMapper = new ObjectMapper();
    
    //
    // Input data options
    //
    
    @Option(name = "--input-path", description="Path to ACHE data target folder", required=true)
    private String inputPath;

    @Option(name={"--repository-type", "-rt"}, description="Which repository type should be used")
    private RepositoryType repositoryType = RepositoryType.FILES;
    
    public enum RepositoryType { 
        FILES, FILESYSTEM_JSON;
    }

    @Option(name="--fs-hashed", description="Whether ACHE filesystem repository files names are hashed")
    private boolean hashFilename = false;
    
    @Option(name="--fs-compressed", description="Whether ACHE filesystem repository files is compressed")
    private boolean compressData = false;
    
    //
    // Options for output data format
    //
    
    @Option(name="--cdr-version", description="Which CDR version should be used")
    private CDRVersion cdrVersion = CDRVersion.CDRv31;
    
    public enum CDRVersion {
        CDRv2, CDRv3, CDRv31
    }
    
    @Option(name="--output-file", description="Gziped output file containing data formmated as per CDR schema")
    private String outputFile;
    
    // Elastic Search output options
    
    @Option(name={"--output-es-index", "-oi"}, description="ElasticSearch index name (output)")
    String outputIndex;
    
    @Option(name={"--output-es-type", "-ot"}, description="ElasticSearch index type (output)")
    String outputType;

    @Option(name={"--output-es-url", "-ou"}, description="ElasticSearch full HTTP URL address")
    String elasticSearchServer = null;
    
    @Option(name={"--output-es-auth", "-oa"}, description="User and password for ElasticSearch in format: user:pass")
    String userPass = null;
    
    @Option(name={"--output-es-bulk-size", "-obs"}, description="ElasticSearch bulk size")
    int bulkSize = 25;

	//AWS S3 Support
	
    @Option(name={"--accesskey", "-ak"}, description="AWS ACCESS KEY ID")
	String accessKeyID = "";

    @Option(name={"--secretkey", "-sk"}, description="AWS SECRET KEY ID")
	String secretKeyID = "";

    @Option(name = {"--bucket", "-bk"}, description = "AWS S3 BUCKET NAME")
    String bucketName = "";
    
    @Option(name = {"--region", "-rg"}, description = "AWS S3 Region name")
    String region = "us-east-1";

    @Option(name = {"--tmp-path", "-tmp"}, description = "Path to temporary working folder")
    String temp = null;

    private PersistentHashtable<CDR31MediaObject> mediaObjectCache;
    private S3Uploader s3Uploader;

    //
    // Runtime variables
    //
    private int processedPages = 0;
    private PrintWriter out;
    private SimpleBulkIndexer bulkIndexer;
    private String id;
    private Object doc;
    
    public static void main(String[] args) throws Exception {
        CliTool.run(args, new AcheToCdrExporter());
    }
    
    @Override
    public void execute() throws Exception {
        
        System.out.println("Reading ACHE data from: "+inputPath);
        System.out.println("Generating CDR file at: "+outputFile);
        System.out.println(" Compressed repository: "+compressData);
        System.out.println("      Hashed file name: "+hashFilename);
        
        if (temp == null) {
            Path tmpPath = Files.createTempDirectory("cdr-export-tmp");
            Files.createDirectories(tmpPath);
            temp = tmpPath.toString();
        }

        s3Uploader = new S3Uploader(this.accessKeyID, this.secretKeyID, this.bucketName, this.region);
        mediaObjectCache =
                new PersistentHashtable<CDR31MediaObject>(temp, 1000, CDR31MediaObject.class);

        if (outputFile != null) {
            GZIPOutputStream gzipStream = new GZIPOutputStream(new FileOutputStream(outputFile));
            out = new PrintWriter(gzipStream, true);
        }

        if (elasticSearchServer != null) {
            if (this.outputIndex == null || this.outputIndex.isEmpty())
                throw new IllegalArgumentException(
                        "Argument for Elasticsearch index can't be empty");
            if (this.outputType == null || this.outputType.isEmpty())
                throw new IllegalArgumentException(
                        "Argument for Elasticsearch type can't be empty");
            bulkIndexer = new SimpleBulkIndexer(elasticSearchServer, userPass, bulkSize);
        }
        
        Iterator<TargetModelJson> it;
        Iterator<TargetModelJson> it1;
        if(repositoryType == RepositoryType.FILESYSTEM_JSON) {
            FileSystemTargetRepository repository = new FileSystemTargetRepository(inputPath,
                    DataFormat.JSON, hashFilename, compressData);
            it = repository.iterator();
            it1 = repository.iterator();
        } else {
            FilesTargetRepository repository = new FilesTargetRepository(inputPath);
            it = repository.iterator();
            it1 = repository.iterator();
        }

		//Process media files
        while (it.hasNext()) {
            TargetModelJson pageModel = it.next();
            try{
				processMediaFile(pageModel);
            } catch(Exception e) {
                System.err.println("Failed to process record.\n" + e.toString());
            }
		}
		
		//Process html files
        while (it1.hasNext()) {
            TargetModelJson pageModel = it1.next();
            try{
                processRecord(pageModel);
                processedPages++;
                if(processedPages % 100 == 0) {
                    System.out.printf("Processed %d pages\n", processedPages);
                }
            } catch(Exception e) {
                System.err.println("Failed to process record.\n" + e.toString());
            }
        }
        System.out.printf("Processed %d pages\n", processedPages);
        
        if(out != null) out.close();
        if(bulkIndexer!= null) bulkIndexer.close();
        
        System.out.println("done.");
    }

	private void processMediaFile(TargetModelJson pageModel) throws IOException {
		// What if contentType is empty but the object is an image. 
		//
        String contentType = pageModel.getContentType();
		
        if (contentType == null || contentType.isEmpty()) {
            System.err.println("Ignoring URL with no content-type: " + pageModel.getUrl());
            return;
        }

		if (!contentType.startsWith("image")) {
			return;
		}

		if (cdrVersion != CDRVersion.CDRv31) {
			return;
		}

		createCDR31MediaObject(pageModel);
	}

    private void processRecord(TargetModelJson pageModel) throws IOException {
        String contentType = pageModel.getContentType();

        if (contentType == null || contentType.isEmpty()) {
            System.err.println("Ignoring URL with no content-type: " + pageModel.getUrl());
            return;
        }

        if (!contentType.startsWith("text/html")) {
            return;
        }

        if (cdrVersion == CDRVersion.CDRv31) {
            createCDR31DocumentJson(pageModel);
        } else if (cdrVersion == CDRVersion.CDRv2) {
            createCDR2DocumentJson(pageModel);
        } else {
            createCDR3DocumentJson(pageModel);
        }

        if (doc != null && out != null) {
            out.println(jsonMapper.writeValueAsString(doc));
        }

        if (bulkIndexer != null) {
            bulkIndexer.addDocument(outputIndex, outputType, doc, id);
        }

    }

    public void createCDR2DocumentJson(TargetModelJson pageModel) {
        HashMap<String, Object> crawlData = new HashMap<>();
        crawlData.put("response_headers", pageModel.getResponseHeaders());
        
        CDR2Document.Builder builder = new CDR2Document.Builder()
                .setUrl(pageModel.getUrl())
                .setTimestamp(pageModel.getFetchTime())
                .setContentType(pageModel.getContentType())
                .setVersion("2.0")
                .setTeam("NYU")
                .setCrawler("ACHE")
                .setRawContent(pageModel.getContentAsString())
                .setCrawlData(crawlData);

        CDR2Document doc = builder.build();
        this.id = doc.getId();
        this.doc = doc;
    }
    
    public void createCDR3DocumentJson(TargetModelJson pageModel) {
        HashMap<String, Object> crawlData = new HashMap<>();
        crawlData.put("response_headers", pageModel.getResponseHeaders());
        
        CDR3Document.Builder builder = new CDR3Document.Builder()
                .setUrl(pageModel.getUrl())
                .setTimestampCrawl(new Date(pageModel.getFetchTime()))
                .setTimestampIndex(new Date())
                .setContentType(pageModel.getContentType())
                .setTeam("NYU")
                .setCrawler("ACHE")
                .setRawContent(pageModel.getContentAsString());

        CDR3Document doc = builder.build();
        this.id = doc.getId();
        this.doc = doc;
    }

	public void createCDR31MediaObject(TargetModelJson pageModel) throws IOException {
		// Hash and upload to S3
		String storedUrl = this.uploadMediaFile(pageModel.getContent(), pageModel.getUrl()); 
        System.out.println("Uploaded object: " + storedUrl);

		// Create Media Object for the image
        CDR31MediaObject obj = new CDR31MediaObject();
		obj.setContentType(pageModel.getContentType());
        obj.setTimestampCrawl(new Date(pageModel.getFetchTime()));
        obj.setObjOriginalUrl(pageModel.getUrl());
		obj.setObjStoredUrl(storedUrl);
        obj.setResponseHeaders(pageModel.getResponseHeaders());

		//Save it for including into the HTML pages later
		this.mediaObjectCache.put(pageModel.getUrl(), obj);
	}
	
    private String uploadMediaFile(byte[] content, String url) throws IOException {
        HashFunction hf = Hashing.sha256();
        Hasher hasher = hf.newHasher();
        hasher.putBytes(content);
        String host = new URL(url).getHost();
        String hs = reverseDomain(host) + "/" + hasher.hash().toString();
        this.s3Uploader.upload(hs, content);
        return hs;
	}

	public String[] extractImgLinks(String html) {
		HashSet<String> links = new HashSet<>();
		Document doc = Jsoup.parse(html);
        Elements media = doc.select("[src]");

        for (Element src : media) {
            if (src.tagName().equals("img")) {
            	links.add(src.attr("abs:src"));
			}
        }
		return links.toArray(new String[links.size()]);
	}

    public void createCDR31DocumentJson(TargetModelJson pageModel) {
        List<CDR31MediaObject> mediaObjects = new ArrayList<>();
        String[] imgLinks = extractImgLinks(pageModel.getContentAsString());
        for (String link : imgLinks) {
            CDR31MediaObject object = this.mediaObjectCache.get(link);
            if (object != null) {
                mediaObjects.add(object);
            }
        }

        CDR31Document.Builder builder = new CDR31Document.Builder()
                .setUrl(pageModel.getUrl())
                .setTimestampCrawl(new Date(pageModel.getFetchTime()))
                .setTimestampIndex(new Date())
                .setContentType(pageModel.getContentType())
                .setResponseHeaders(pageModel.getResponseHeaders())
                .setRawContent(pageModel.getContentAsString())
				.setObjects(mediaObjects)
                .setTeam("NYU")
                .setCrawler("ACHE");

        CDR31Document doc = builder.build();
        this.id = doc.getId();
        this.doc = doc;
    }
    
    private String reverseDomain(String domain) {
        if(domain == null || domain.isEmpty()) {
            return null;
        }
        String[] hostParts = domain.split("\\.");
        if(hostParts.length == 0 ) {
            return null;
        }
        StringBuilder reverseDomain = new StringBuilder();
        reverseDomain.append(hostParts[hostParts.length-1]);
        for (int i = hostParts.length-2; i >= 0; i--) {
            reverseDomain.append('/');
            reverseDomain.append(hostParts[i]);
        }
        return reverseDomain.toString();
    }

}
