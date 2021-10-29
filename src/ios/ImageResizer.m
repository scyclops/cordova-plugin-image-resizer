#import "ImageResizer.h"
#import <Cordova/CDV.h>
#import <Cordova/CDVPluginResult.h>
#import <AssetsLibrary/AssetsLibrary.h>

#define TIMESTAMP [NSString stringWithFormat:@"%f",[[NSDate date] timeIntervalSince1970] * 1000]

@implementation ImageResizer {
    UIImage* sourceImage;
}

- (void) resize:(CDVInvokedUrlCommand*)command
{
    __weak ImageResizer* weakSelf = self;

    [self.commandDelegate runInBackground:^{

        NSLog(@"image resizer resize running");

        __block PHImageRequestOptions * imageRequestOptions = [[PHImageRequestOptions alloc] init];
        
        imageRequestOptions.synchronous = YES;
        
        // get the arguments and the stuff inside of it
        NSDictionary* arguments = [command.arguments objectAtIndex:0];
        NSString* imageUrlString = [arguments objectForKey:@"uri"];
        NSLog(@"image resizer image url: %@", imageUrlString);
        
        NSString* quality = [arguments objectForKey:@"quality"];
        CGSize frameSize = CGSizeMake([[arguments objectForKey:@"width"] floatValue], [[arguments objectForKey:@"height"] floatValue]);
        NSString* fileName = [arguments objectForKey:@"fileName"];
        
        // Check if the file is a local file, and if so, read with file manager to avoid NSUrl -1022 error
        if ([[NSFileManager defaultManager] fileExistsAtPath:imageUrlString]) {
            sourceImage = [UIImage imageWithData: [[NSFileManager defaultManager] contentsAtPath:imageUrlString]];
        } else {
            sourceImage = [UIImage imageWithData: [NSData dataWithContentsOfURL: [NSURL URLWithString:imageUrlString]]];
        }    
        
        PHFetchResult *savedAssets = [PHAsset fetchAssetsWithLocalIdentifiers:@[fileName] options:nil];
        [savedAssets enumerateObjectsUsingBlock:^(PHAsset *asset, NSUInteger idx, BOOL *stop) {
            //this gets called for every asset from its localIdentifier you saved
            
            [[PHImageManager defaultManager]
             requestImageDataForAsset:asset
             options:imageRequestOptions
             resultHandler:^(NSData *imageData, NSString *dataUTI,
                             UIImageOrientation orientation,
                             NSDictionary *info)
             {
                 sourceImage  = [UIImage imageWithData:imageData];
             }];
            
        }];
        
        NSLog(@"image resizer sourceImage: %@", (sourceImage ? @"image exists" : @"null" ));
        
        UIImage *tempImage = nil;
        CGSize targetSize = frameSize;
        
        CGRect thumbnailRect = CGRectMake(0, 0, 0, 0);
        thumbnailRect.origin = CGPointMake(0.0,0.0);
        
        // get original image dimensions
        CGFloat heightInPoints = sourceImage.size.height;
        CGFloat heightInPixels = heightInPoints * sourceImage.scale;
        CGFloat widthInPoints = sourceImage.size.width;
        CGFloat widthInPixels = widthInPoints * sourceImage.scale;
        
        // calculate the target dimensions in a way that preserves the original aspect ratio
        CGFloat newWidth = targetSize.width;
        CGFloat newHeight = targetSize.height;
        
        if (heightInPixels > widthInPixels) {
            // vertical image: use targetSize.height as reference for scaling
            newWidth = widthInPixels * newHeight / heightInPixels;
        } else {
            // horizontal image: use targetSize.width as reference
            newHeight = heightInPixels * newWidth / widthInPixels;
        }
        
        thumbnailRect.size.width  = newWidth;
        thumbnailRect.size.height = newHeight;
        targetSize.width = newWidth;
        targetSize.height = newHeight;
        
        UIGraphicsBeginImageContext(targetSize);
        [sourceImage drawInRect:thumbnailRect];
        
        tempImage = UIGraphicsGetImageFromCurrentImageContext();
        NSLog(@"image resizer tempImage: %@", (tempImage  ? @"image exsist" : @"null" ));
        
        UIGraphicsEndImageContext();
        NSData *imageData = UIImageJPEGRepresentation(tempImage, [quality floatValue] / 100.0f );
        NSArray *paths = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES);
        NSString *cachesDirectory = [paths objectAtIndex:0];
        BOOL isDir = NO;
        NSError *error;
        if (! [[NSFileManager defaultManager] fileExistsAtPath:cachesDirectory isDirectory:&isDir] && isDir == NO) {
            [[NSFileManager defaultManager] createDirectoryAtPath:cachesDirectory withIntermediateDirectories:NO attributes:nil error:&error];
        }
        NSString *imagePath =[cachesDirectory stringByAppendingPathComponent:[NSString stringWithFormat:@"img%@.jpeg", TIMESTAMP]];
        CDVPluginResult* result = nil;
        
        if (![imageData writeToFile:imagePath atomically:NO]) {
            result = [CDVPluginResult resultWithStatus:CDVCommandStatus_IO_EXCEPTION messageAsString:@"error save image"];
        } else {
            result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:[[NSURL fileURLWithPath:imagePath] absoluteString]];
        }
        
        [weakSelf.commandDelegate sendPluginResult:result callbackId:command.callbackId];
    }];
}

@end
