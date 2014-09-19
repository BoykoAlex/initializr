/*
 * Copyright 2012-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.spring.initializr.web

import groovy.util.logging.Slf4j
import io.spring.initializr.InitializrMetadata
import io.spring.initializr.ProjectGenerator
import io.spring.initializr.ProjectRequest
import io.spring.initializr.flux.UploadOperation

import org.apache.commons.io.IOUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

/**
 * The main initializr controller provides access to the configured
 * metadata and serves as a central endpoint to generate projects
 * or build files.
 *
 * @author Dave Syer
 * @author Stephane Nicoll
 * @since 1.0
 */
@Controller
@Slf4j
class MainController extends AbstractInitializrController {

	@Autowired
	private ProjectGenerator projectGenerator
	
	private static final String INIT_HOME_REDIRECT_URL = System.getenv("INIT_HOME_REDIRECT_URL") == null ? "http://localhost:8080/home" : System.getenv("INIT_HOME_REDIRECT_URL")
	private static final String FLUX_URL = System.getenv("FLUX_URL") == null ? "http://localhost:3000" : System.getenv("FLUX_URL")
	private static final String GITHUB_CLIENT_ID = System.getenv("GITHUB_CLIENT_ID") == null ? "90e70185cf2f97322261" : System.getenv("GITHUB_CLIENT_ID")
	private static final String GITHUB_CLIENT_SECRET = System.getenv("GITHUB_CLIENT_SECRET") == null ? "082e048051152139c1c39ae09c1feb7f4e0387cf" : System.getenv("GITHUB_CLIENT_SECRET")

	private static final String UTF_8 = "UTF-8"

	@ModelAttribute
	ProjectRequest projectRequest() {
		def request = new ProjectRequest()
		metadataProvider.get().initializeProjectRequest(request)
		request
	}

	@RequestMapping(value = "/")
	@ResponseBody
	InitializrMetadata metadata() {
		metadataProvider.get()
	}

	@RequestMapping(value = '/', produces = 'text/html')
	String home() {
		"redirect:https://github.com/login/oauth/authorize?client_id=" + GITHUB_CLIENT_ID + "&redirect_uri=" + INIT_HOME_REDIRECT_URL
	}

	@RequestMapping(value = '/home', produces = 'text/html', params = [ "code" ])
	@ResponseBody
	String homePage(@RequestParam(value = "code") String code) {
		renderHome('home.html', code)
	}
	
	@RequestMapping('/spring')
	String spring() {
		def url = metadataProvider.get().createCliDistributionURl('zip')
		"redirect:$url"
	}

	@RequestMapping(value = ['/spring.tar.gz', 'spring.tgz'])
	String springTgz() {
		def url = metadataProvider.get().createCliDistributionURl('tar.gz')
		"redirect:$url"
	}

	@RequestMapping('/pom')
	@ResponseBody
	ResponseEntity<byte[]> pom(ProjectRequest request) {
		def mavenPom = projectGenerator.generateMavenPom(request)
		new ResponseEntity<byte[]>(mavenPom, ['Content-Type': 'application/octet-stream'] as HttpHeaders, HttpStatus.OK)
	}

	@RequestMapping('/build')
	@ResponseBody
	ResponseEntity<byte[]> gradle(ProjectRequest request) {
		def gradleBuild = projectGenerator.generateGradleBuild(request)
		new ResponseEntity<byte[]>(gradleBuild, ['Content-Type': 'application/octet-stream'] as HttpHeaders, HttpStatus.OK)
	}

	@RequestMapping('/starter.zip')
	@ResponseBody
	ResponseEntity<byte[]> springZip(ProjectRequest request) {
		def dir = projectGenerator.generateProjectStructure(request)

		def download = projectGenerator.createDistributionFile(dir, '.zip')

		new AntBuilder().zip(destfile: download) {
			zipfileset(dir: dir, includes: '**')
		}
		log.info("Uploading: ${download} (${download.bytes.length} bytes)")
		def result = new ResponseEntity<byte[]>(download.bytes,
				['Content-Type': 'application/zip'] as HttpHeaders, HttpStatus.OK)

		projectGenerator.cleanTempFiles(dir)
		result
	}

	@RequestMapping('/flux')
	String flux(ProjectRequest request) {
		def dir = projectGenerator.generateProjectStructure(request)

		String url = "https://github.com/login/oauth/access_token?client_id=" + GITHUB_CLIENT_ID + "&client_secret=" + GITHUB_CLIENT_SECRET + "&code=" + request.code
		URLConnection connection = new URL(url).openConnection();
		connection.setDoOutput(true); // Triggers POST.
		connection.setRequestProperty("Accept-Charset", UTF_8);
		connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=" + UTF_8);
		
		String s = IOUtils.toString(connection.getInputStream())
		
		def matcher = s =~ /.*access_token=(\w+)[^&]*/
		if (matcher.find()) {
			String token =  matcher.group(1)
			String userUrl = "https://api.github.com/user?access_token=" + token
//			def slurper = new JsonSlurper()
//			def json = slurper.parse(new URL(userUrl))
//			println json
			connection = new URL(userUrl).openConnection()
			connection.setRequestProperty("Accept-Charset", UTF_8);
			connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=" + UTF_8);
			String responseBody = IOUtils.toString(connection.getInputStream())
			matcher = responseBody =~ /.*"login":"(\w+)".*/
			if (matcher.find()) {
				String username = matcher.group(1)
				String tmpUrl = FLUX_URL.substring(FLUX_URL.indexOf(":"))
				String encodedName = encodeURIComponent(request.name)
				String encodedPackage = encodeURIComponent(request.packageName)
				String appendUrl = FLUX_URL.substring(FLUX_URL.indexOf(":"))
				String fluxUrl = FLUX_URL + "/edit/edit.html#flux" + tmpUrl + "/" + encodedName + "/src/main/java/" + encodedPackage + "/Application.java"
				new UploadOperation(FLUX_URL, username, token, dir, request.name).run()
				projectGenerator.cleanTempFiles(dir)
				"redirect:$fluxUrl"
			} else {
				throw new RuntimeException("Cannot fetch GitHub credentials :-(")
			}
		} else {
			throw new RuntimeException("Github pass code has expired: " + s)
		}
		
	}
	
	@RequestMapping(value='/starter.tgz', produces='application/x-compress')
	@ResponseBody
	ResponseEntity<byte[]> springTgz(ProjectRequest request) {
		def dir = projectGenerator.generateProjectStructure(request)

		def download = projectGenerator.createDistributionFile(dir, '.tgz')

		new AntBuilder().tar(destfile: download, compression: 'gzip') {
			zipfileset(dir:dir, includes:'**')
		}
		log.info("Uploading: ${download} (${download.bytes.length} bytes)")
		def result = new ResponseEntity<byte[]>(download.bytes,
				['Content-Type':'application/x-compress'] as HttpHeaders, HttpStatus.OK)

		projectGenerator.cleanTempFiles(dir)
		result
	}

	public static String encodeURIComponent(String s) {
		String result;
	
		try {
			result = URLEncoder.encode(s, UTF_8)
					.replaceAll("\\+", "%20")
					.replaceAll("\\%21", "!")
					.replaceAll("\\%27", "'")
					.replaceAll("\\%28", "(")
					.replaceAll("\\%29", ")")
					.replaceAll("\\%7E", "~");
		} catch (UnsupportedEncodingException e) {
			result = s;
		}
	
		return result;
	}
}
