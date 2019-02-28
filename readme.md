steps:
1. clean target/dist folder
--> mvn clean
 	will clear target directory

2. creating executable jar file
--> mvn install/package
	will create jar file with support jars inside package
	it will have memory more than 20 MB
	
3. run jar file 
	copy jar file to a folder and create following folders in it.
	> mkdir data
	> mkdir logs
	and create your own config file for feed to simulator, like 'simulator.config'
	run the following command 
	--> java -jar <simulator-x.x.x jar name> <config file name> 
	
	will run simulator with your configurations.....
	    
	 	  