/*
 * Copyright 2013 Nokia Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nokia.util.avro.schemagen;

import com.nokia.util.avro.types.AvroType;
import com.nokia.util.avro.types.NamedAvroType;
import com.sun.codemodel.JPackage;
import com.sun.codemodel.fmt.JTextFile;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.Plugin;
import com.sun.tools.xjc.model.CClassInfo;
import com.sun.tools.xjc.model.CEnumLeafInfo;
import com.sun.tools.xjc.model.Model;
import com.sun.tools.xjc.model.nav.NClass;
import com.sun.tools.xjc.outline.Outline;
import com.sun.tools.xjc.outline.PackageOutline;
import org.codehaus.jackson.annotate.JsonAutoDetect;
import org.codehaus.jackson.map.ObjectMapper;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

import java.io.File;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Ben Fagin (Nokia)
 * @version 01-16-2012
 *
 * XJC plugin which walks the generated code model and creates a parallel Avro schema.
 * Running the plugin requires making sure that XJC can see this compiled class
 * at runtime when it is identifying plugins.
 *
 * As well, a special META-INF file called 'com'sun.tools.xjc.Plugin' must be created
 * with a reference to this plugin class. This is how XJC detects new plugin services
 * at runtime.
 *
 *
 * TODO maybe switch to IDL format instead of individual schemas.
 */
public class AvroSchemagenPlugin extends Plugin {
	public static final String PLUGIN_NAME = "XavroSchemagen";
	private static final String OUTPUT_DIRECTORY_ORDERED = "avro-schemas-ordered";
	private static final String OUTPUT_DIRECTORY = "avro-schemas";
	protected static ObjectMapper mapper = new ObjectMapper();
	private SchemagenHelper helper;

	static {
		// don't auto detect anything that we haven't annotated
		mapper.setVisibilityChecker(mapper.getVisibilityChecker().with(JsonAutoDetect.Visibility.NONE));
	}


	/**
	 * Returns the directory which the schemas have been written to.
	 *
	 * @param outputDirectory base directory of the output
	 * @return the schema directory, relative to the base output directory
	 */
	public static File getSchemaDirectory(File outputDirectory) {
		return new File(outputDirectory.getAbsoluteFile() + File.separator + OUTPUT_DIRECTORY_ORDERED);
	}

	/*
		XJC run method.
	 */
	@Override
	public boolean run(Outline outline, Options options, ErrorHandler errorHandler) throws SAXException {
		helper = new SchemagenHelper(outline);

		// set up special schemas
		helper.getSpecialSchemas().put(ReferenceAvroType.class, new HashSet<>());
		helper.getSpecialSchemas().put(DateAvroType.class, new HashSet<>());

		// do the work
		inferAvroSchema(outline);	

		// true because we're done and processing should continue
		return true;
	}

	/*
		Infer schemas from enums and beans, then generate the files.
	 */
	private void inferAvroSchema(Outline outline) {
		Model model = outline.getModel();
		Set<NamedAvroType> avroTypes = new HashSet<>();

		// enums
		for (Map.Entry<NClass, CEnumLeafInfo> entry : model.enums().entrySet()) {
			CEnumLeafInfo info = entry.getValue();
			NamedAvroType type = helper.avroFromEnum(info);
			avroTypes.add(type);
		}
		
		// regular classes
		for (Map.Entry<NClass, CClassInfo> entry : model.beans().entrySet()) {
			CClassInfo info = entry.getValue();
			NamedAvroType type = helper.avroFromClass(info);
			avroTypes.add(type);
		}
		
		// grab a package context, write the schemas
		PackageOutline aPackage = outline.getAllPackageContexts().iterator().next();
		JPackage rootPackage = aPackage._package().owner().rootPackage();
		generateAvroSchemas(rootPackage, avroTypes);
	}



	/*
		Generate the actual Avro schemas and write to file system.
		Checks for name collisions, sorts by dependencies, writes out schema files, special schemas,
		and debug summary information.
	 */
	private void generateAvroSchemas(JPackage rootPackage, Set<NamedAvroType> avroTypes) {
		JPackage avroPackageOrdered = rootPackage.subPackage(OUTPUT_DIRECTORY_ORDERED);
		JPackage avroPackage = rootPackage.subPackage(OUTPUT_DIRECTORY);
		System.out.println("Writing schemas under packages: " + avroPackageOrdered.name()
				+ ", " + avroPackageOrdered.name());

		// check for name collisions
		checkForCollisions(avroTypes);

		// sort results by dependency
		List<NamedAvroType> orderedTypes = topologicalSort(avroTypes);
		
		// add in special types
		for (String ns : helper.getSpecialSchemas().get(DateAvroType.class)) {
			orderedTypes.add(0, DateAvroType.getSchema(ns));
		}

		for (String ns : helper.getSpecialSchemas().get(ReferenceAvroType.class)) {
			orderedTypes.add(0, ReferenceAvroType.getSchema(ns));
		}

		// output in an ordered way
		outputSchema(avroPackageOrdered, avroPackage, orderedTypes);

		// output debug summary
		JTextFile avroSchema = new JTextFile("avro-schemas.avsc");
		StringBuilder sb = new StringBuilder();

		for (NamedAvroType type : orderedTypes) {
			sb.append(getJson(type));
		}
		
		avroSchema.setContents(sb.toString());
		rootPackage.addResourceFile(avroSchema);
	}

	/*
	 * Checks for collisions with type names.
	 *
	 * TODO can be made more specific by checking names per namespace rather than globally.
	 */
	private void checkForCollisions(Set<NamedAvroType> types) {
		Set<String> names = new HashSet<>();

		for (Iterator<NamedAvroType> it = types.iterator(); it.hasNext();) {
			NamedAvroType type = it.next();
			String name = type.name;

			if (names.contains(name)) {
				System.out.println("Duplicate type names found under '" + name);
				it.remove();
				//throw new SchemagenException("Duplicate type names found under '" + name + "'!");
			} else {
				names.add(name);
			}
		}
	}

	/*
		Dependency sort, done via a standard topological sort.
		As types are encountered for the first time, they are stubbed,
		with the assumption that they will come up later.

		TODO Though circular references shouldn't be possible, it might be good to check.
	 */
	private List<NamedAvroType> topologicalSort(Set<NamedAvroType> types) {
		Map<String, Node> nodesByName = new HashMap<>();

		for (NamedAvroType type : types) {
			String typeName = type.namespace + "." + type.name;
			Node node = nodesByName.get(typeName);

			// create if not present
			if (node == null) {
				node = new Node(type);
				nodesByName.put(typeName, node);

			// stub needs its type set
			} else {
				node.type = type;
			}

			for (String dependentType : type.getDependencies()) {
				Node dependent = nodesByName.get(dependentType);
	
				// stub
				if (dependent == null) {
					dependent = new Node(null);
					nodesByName.put(dependentType, dependent);
				}
	
				// create the dependency
				node.parents.add(dependent);
				dependent.children.add(node);
			}
		}

		// turn into an ordered list
		Set<Node> allNodes = new HashSet<>(nodesByName.values());
		List<NamedAvroType> sequence = new ArrayList<>();

		while (!allNodes.isEmpty()) {
			Set<Node> removals = new HashSet<>();
			
			for (Node node : allNodes) {
				if (node.parents.isEmpty()) {
					sequence.add(node.type);

					for (Node child : node.children) {
						child.parents.remove(node);
					}
					
					removals.add(node);
				}
			}
			
			allNodes.removeAll(removals);
		}

		return sequence;
	}

	/*
		Write the actual schema files. Uses a counter to keep files ordered on the filesystem,
		using 0 prefixes where necessary to guarantee ordering.
	 */
	private void outputSchema(JPackage avroPackageOrdered, JPackage avroPackage, List<NamedAvroType> types) {
		// set up the correct format for leading zeros (ensures proper order in filesystem)
		StringBuilder digits = new StringBuilder();
		for (int i=0; i < Integer.toString(types.size()).length(); ++i) {
			digits.append("0");
		}

		DecimalFormat format = new java.text.DecimalFormat(digits.toString());
		AtomicInteger counter = new AtomicInteger(1);

		for (NamedAvroType type : types) {
			String id = format.format(counter.getAndIncrement());
			String jsonValue = getJson(type);

			JTextFile avroSchemaWithId = new JTextFile(id + "_"+ type.name + ".avsc");
			avroSchemaWithId.setContents(jsonValue);
			avroPackageOrdered.addResourceFile(avroSchemaWithId);

			JTextFile avroSchema = new JTextFile(type.name + ".avsc");
			avroSchema.setContents(jsonValue);
			avroPackage.subPackage(type.namespace.replace(".", "/")).addResourceFile(avroSchema);
		}
	}

	/**
	 * Use the Jackson mapper to create a JSON string from an Avro schema object.
	 *
	 * @param type the type to map
	 * @return a JSON schema string for the type
	 */
	private String getJson(AvroType type) {
		try {
			return replaceValues(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(type));
		} catch (Exception ex) {
			throw new SchemagenException(ex);
		}
	}

	private String replaceValues(String value) {
		return value.replace("\"[]\"", "[]")
				.replace("\"default\" : \"null\"", "\"default\" : null");
	}
	/**
	 * Node class supporting Avro types. Used for dependency sorting.
	 */
	private static class Node {
		NamedAvroType type;
		final Set<Node> parents = new HashSet<>();
		final Set<Node> children = new HashSet<>();
		
		Node(NamedAvroType type) {
			this.type = type;
		}
	}

	// other XJC plugin methods

	/**
	 * @return the name of this plugin
	 */
	@Override
	public String getOptionName() {
		return PLUGIN_NAME;
	}

	/**
	 * @return the usage string for this plugin
	 */
	@Override
	public String getUsage() {
		return "  -"+ PLUGIN_NAME +"    :  generate a parallel avro schema";
	}
}