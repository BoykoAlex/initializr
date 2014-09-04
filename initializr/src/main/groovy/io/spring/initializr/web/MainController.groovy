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
import io.spring.initializr.flux.UploadOperation2

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
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
	@ResponseBody
	String home() {
		renderHome('home.html')
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
//	@ResponseBody
	String flux(ProjectRequest request) {
		def dir = projectGenerator.generateProjectStructure(request)

//		def download = projectGenerator.createDistributionFile(dir, '.zip')
//
//		new AntBuilder().zip(destfile: download) {
//			zipfileset(dir: dir, includes: '**')
//		}
//		log.info("Uploading: ${download} (${download.bytes.length} bytes)")
//		def result = new ResponseEntity<byte[]>(download.bytes,
//				['Content-Type': 'application/zip'] as HttpHeaders, HttpStatus.OK)

//		projectGenerator.cleanTempFiles(dir)
		
		String fluxUrl = "http://localhost:3000"
		
		new UploadOperation2(fluxUrl, "defaultuser", null, dir, request.name).run()
		"redirect:$fluxUrl"
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

}
